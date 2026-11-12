package com.xarvis.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PatchTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())
    private fun tmp() = File.createTempFile("out", ".bin").apply { deleteOnExit() }

    @Test fun rebuildsTheNewFileExactly() {
        // small.xvpatch was made by .github/scripts/make_patches.py from old.bin to new.bin.
        val out = tmp()
        val sha = Patch.apply(res("old.bin"), res("small.xvpatch"), out)
        assertTrue(res("new.bin").readBytes().contentEquals(out.readBytes()))
        assertEquals(Patch.sha256(res("new.bin")), sha)
        assertEquals("e60a67520d36fd67496a0aac09a3dc9d7b11e831c41427e9a3828c87e41c69d2", sha)
    }

    @Test fun aDifferentStartingFileGivesADifferentFingerprint() {
        // The screen compares the fingerprint with GitHub's, so this can never be installed.
        val other = tmp().apply { writeBytes(res("old.bin").readBytes().also { it[100] = (it[100] + 1).toByte() }) }
        val out = tmp()
        val sha = Patch.apply(other, res("small.xvpatch"), out)
        assertNotEquals(Patch.sha256(res("new.bin")), sha)
    }

    @Test fun damagedPatchesAreRefused() {
        val good = res("small.xvpatch").readBytes()
        val cases = listOf(
            "not a patch" to "hello world, definitely not a patch file".toByteArray(),
            "cut short" to good.copyOf(good.size / 2),
            "header" to good.copyOf().also { it[15] = 0x7f }, // a huge new size
            "body" to good.copyOf().also { for (i in 40 until 60) it[i] = 0 },
        )
        for ((what, bytes) in cases) {
            val p = tmp().apply { writeBytes(bytes) }
            try {
                val sha = Patch.apply(res("old.bin"), p, tmp())
                // A damaged body that still inflates must at least never match.
                assertNotEquals(what, Patch.sha256(res("new.bin")), sha)
            } catch (e: Patch.Damaged) {
                // refused: good
            } catch (e: Exception) {
                fail("$what: ${e.javaClass.simpleName} instead of Patch.Damaged")
            }
        }
    }
}

class ReleaseTest {
    private val json = """
        {"version": "1.0.263",
         "files": {"XARVIS.apk": {"sha256": "AAA", "size": 107311366}, "XARVIS-benco.apk": {"sha256": "bbb", "size": 62084550}},
         "patches": [
           {"file": "XARVIS.apk", "from": "1.0.261", "fromSha256": "s22-261", "asset": "XARVIS-v1.0.261-to-v1.0.263.patch", "size": 246210, "sha256": "p1"},
           {"file": "XARVIS-benco.apk", "from": "1.0.259", "fromSha256": "benco-259", "asset": "XARVIS-benco-v1.0.259-to-v1.0.263.patch", "size": 190000, "sha256": "p2"}
         ]}
    """.trimIndent()

    @Test fun readsPatchesJson() {
        val r = Release.parse(json)
        assertEquals("1.0.263", r.version)
        assertEquals(Release.FileInfo("aaa", 107311366), r.files["XARVIS.apk"])
        assertEquals("XARVIS-benco-v1.0.259-to-v1.0.263.patch", r.patchFor("BENCO-259")?.asset)
        assertNull(r.patchFor("something else"))
    }

    @Test fun choosesThePatchByTheInstalledFile() {
        val r = Release.parse(json)
        // The S22 on v1.0.261: the small patch.
        val s22 = Choice.of("1.0.261", "s22-261", "XARVIS.apk", "1.0.263", r)
        assertTrue(s22 is Choice.ViaPatch)
        assertEquals(246210L, (s22 as Choice.ViaPatch).patch.size)
        assertEquals(107311366L, s22.target.size)
        // The fingerprint decides, even if the build was guessed wrong.
        assertEquals("XARVIS-benco.apk", (Choice.of("1.0.259", "benco-259", "XARVIS.apk", "1.0.263", r) as Choice.ViaPatch).file)
        // Too old (or not from GitHub): the full download, still checked against GitHub's fingerprint.
        assertEquals(Choice.Full("XARVIS.apk", Release.FileInfo("aaa", 107311366)), Choice.of("1.0.240", "old", "XARVIS.apk", "1.0.263", r))
        // Already new, or newer (a test build): nothing to do.
        assertEquals(Choice.UpToDate, Choice.of("1.0.263", "x", "XARVIS.apk", "1.0.263", r))
        assertEquals(Choice.UpToDate, Choice.of("1.0.265", "x", "XARVIS.apk", "1.0.263", r))
        // GitHub hasn't made the patches for the newest release yet (or the list is an older one's).
        assertEquals(Choice.Full("XARVIS.apk", null), Choice.of("1.0.261", "s22-261", "XARVIS.apk", "1.0.263", null))
        assertEquals(Choice.Full("XARVIS.apk", null), Choice.of("1.0.261", "s22-261", "XARVIS.apk", "1.0.265", r))
    }

    @Test fun versionsAndTags() {
        assertTrue(Release.isNewer("1.0.263", "1.0.261"))
        assertFalse(Release.isNewer("1.0.261", "1.0.261"))
        assertFalse(Release.isNewer("test-perms", "1.0.1"))
        assertEquals("v1.0.263", Release.tagFromRedirect("https://github.com/rexraja89-oss/XARVISS/releases/tag/v1.0.263"))
        assertNull(Release.tagFromRedirect(null))
    }
}
