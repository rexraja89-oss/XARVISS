package com.xarvis.ai.code

/**
 * The cloud side of XARVIS game modding. The phone is only the courier: it uploads the game file (OBB/
 * APK/ZIP) to a PRIVATE GitHub repo and fires these GitHub Actions, which run on a real computer with a
 * full toolchain. "inspect" opens the package and reports in plain words what is inside and what can be
 * changed (or why it can't). "repack" rebuilds the package after XARVIS has edited the extracted files.
 *
 * The repo is kept PRIVATE so the game's own data never becomes public. The editing itself is done by the
 * brain ON THE PHONE (where the key already lives) — the cloud only unpacks and repacks.
 */
object GameModPipeline {

    const val REPO = "xarvis-game-mods"
    const val TAG_IN = "input"
    const val TAG_OUT = "output"
    const val INSPECT_WORKFLOW = "inspect.yml"
    const val REPACK_WORKFLOW = "repack.yml"

    /** Files XARVIS pushes into the repo once, as path -> content. */
    fun files(): Map<String, String> = mapOf(
        ".github/workflows/inspect.yml" to INSPECT_YML,
        ".github/workflows/repack.yml" to REPACK_YML,
        ".xarvis/inspect.py" to INSPECT_PY,
        ".xarvis/repack.py" to REPACK_PY,
        ".xarvis/README.md" to README,
    )

    /**
     * Reads the editable-file lines ("  - path (123 bytes)") out of the INSIDE.md report the inspect
     * workflow writes, returning the file paths. Pure, so it is unit-tested.
     */
    fun editableFiles(inside: String): List<String> =
        inside.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").substringBeforeLast(" (").trim() }
            .filter { it.isNotBlank() }
            .toList()

    /** True when the report says the package could not be opened (sealed/encrypted/not an archive). */
    fun isSealed(inside: String): Boolean = inside.contains("SEALED", ignoreCase = false)

    private val INSPECT_YML = """
        name: inspect
        on:
          workflow_dispatch:
            inputs:
              asset:
                description: the uploaded game file name
                required: true
        permissions:
          contents: write
        jobs:
          inspect:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - name: Download the game file
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                run: gh release download $TAG_IN --pattern "${'$'}{{ github.event.inputs.asset }}" --dir _in --clobber
              - name: Open and report
                run: python3 .xarvis/inspect.py "_in/${'$'}{{ github.event.inputs.asset }}"
              - name: Save the report
                run: |
                  git config user.name xarvis
                  git config user.email xarvis@users.noreply.github.com
                  git add -A
                  git commit -m "inspect ${'$'}{{ github.event.inputs.asset }}" || echo "nothing to commit"
                  git push
    """.trimIndent()

    private val REPACK_YML = """
        name: repack
        on:
          workflow_dispatch:
            inputs:
              asset:
                description: the original game file name
                required: true
        permissions:
          contents: write
        jobs:
          repack:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - name: Download the original
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                run: gh release download $TAG_IN --pattern "${'$'}{{ github.event.inputs.asset }}" --dir _in --clobber
              - name: Rebuild with the edits
                run: python3 .xarvis/repack.py "_in/${'$'}{{ github.event.inputs.asset }}" "modified-${'$'}{{ github.event.inputs.asset }}"
              - name: Publish the modified file
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                run: |
                  gh release create $TAG_OUT --title $TAG_OUT --notes "modified by XARVIS" || true
                  gh release upload $TAG_OUT "modified-${'$'}{{ github.event.inputs.asset }}" --clobber
    """.trimIndent()

    private val INSPECT_PY = """
        import sys, zipfile, os
        f = sys.argv[1]
        EDIT = {'.txt','.json','.xml','.ini','.cfg','.conf','.csv','.yaml','.yml','.lua',
                '.js','.html','.properties','.md','.sav','.save','.dat','.prefs'}
        out = []
        os.makedirs('extracted', exist_ok=True)
        if not zipfile.is_zipfile(f):
            out.append("SEALED: this file is not an open archive, so nothing can be unpacked from it.")
            out.append("It may be encrypted or in a custom format that needs the game's own tools.")
            out.append("IDEA: many games keep your editable progress in a SAVE file inside")
            out.append("Android/data/<the game>/ rather than in the OBB. Try handing XARVIS that file instead.")
        else:
            z = zipfile.ZipFile(f)
            names = [n for n in z.namelist() if not n.endswith('/')]
            out.append("OPENED OK - %d files inside the package." % len(names))
            ed = [n for n in names if os.path.splitext(n)[1].lower() in EDIT]
            out.append("EDITABLE text/config files found: %d" % len(ed))
            kept = 0
            for n in ed:
                try:
                    data = z.read(n)
                    if len(data) <= 500000:
                        p = os.path.join('extracted', n)
                        os.makedirs(os.path.dirname(p) or '.', exist_ok=True)
                        with open(p, 'wb') as o: o.write(data)
                        out.append("- %s (%d bytes)" % (n, len(data)))
                        kept += 1
                except Exception as e:
                    out.append("# could not read %s: %s" % (n, e))
            if kept == 0:
                out.append("No hand-editable text/config files. The game's data looks like images, audio")
                out.append("or packed binary, which can't be meaningfully edited by hand.")
                out.append("IDEA: look for a SAVE file in Android/data/<the game>/ - that is usually where")
                out.append("coins, levels and unlocks live, and it is often a small editable file.")
            out.append("NOTE: some games check their OBB and refuse a rebuilt one. If the modified file")
            out.append("does not load, the game has an integrity check and the OBB route is closed for it.")
        with open('INSIDE.md', 'w') as o:
            o.write("\n".join(out))
        print("\n".join(out))
    """.trimIndent()

    private val REPACK_PY = """
        import sys, zipfile, os
        orig, out = sys.argv[1], sys.argv[2]
        zin = zipfile.ZipFile(orig, 'r')
        zout = zipfile.ZipFile(out, 'w')
        replaced = 0
        for item in zin.infolist():
            repl = os.path.join('extracted', item.filename)
            if os.path.isfile(repl):
                with open(repl, 'rb') as fh:
                    zout.writestr(item, fh.read())   # keeps the entry's original compression
                replaced += 1
            else:
                zout.writestr(item, zin.read(item.filename))
        zout.close(); zin.close()
        print("repacked %s, replaced %d file(s)" % (out, replaced))
    """.trimIndent()

    private val README = """
        # XARVIS game-mod workspace

        XARVIS uploads a game's OBB/APK/ZIP here as a private release, then runs the `inspect` and
        `repack` workflows. This repo is private on purpose: the game's data never becomes public.
        You can delete this repo any time; XARVIS will recreate it on the next job.
    """.trimIndent()
}
