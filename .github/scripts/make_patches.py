#!/usr/bin/env python3
"""
Small updates for XARVIS (Nov 2026; Rex: "fed up with massive data consumption"): XARVIS.apk is ~107 MB but a
release usually changes ~1–3 MB of it. For a new release this makes patches from the last few releases' APKs to the
new one, plus patches.json, and attaches them to the release. XARVIS Updater (the :updater app) downloads the patch,
rebuilds the exact new APK from the installed one, checks its SHA-256 and installs it.

Patch format "XVPATCH1" (bsdiff's blocks, deflate instead of bzip2 so Android can read it with java.util.zip):
    b"XVPATCH1" | new size (int64 BE) | control length | diff length | zlib(control) | zlib(diff) | zlib(extra)
control = triples (x, y, z) as int64 BE: add x bytes of diff to old[oldpos:], copy y bytes of extra, oldpos += z.

Usage on GitHub (needs `gh`, GH_TOKEN, `pip install bsdiff4`):
    make_patches.py release --repo OWNER/REPO --tag v1.0.263
Local check:
    make_patches.py one OLD.apk NEW.apk OUT.patch
"""
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import time
import zlib

import bsdiff4.core as core
from bsdiff4.format import unpack_control_block

MAGIC = b"XVPATCH1"
# Which APKs get patches, and from how many earlier releases (the benco is often left a few releases behind).
PATCHED = {"XARVIS.apk": 4, "XARVIS-benco.apk": 6}
# Every APK the Updater can install gets its size and SHA-256 listed (a full download is checked too).
LISTED = ["XARVIS.apk", "XARVIS-benco.apk", "XARVIS-Remote.apk", "XARVIS-Updater.apk"]
# A patch bigger than this share of the full file isn't worth it.
MAX_SHARE = 0.6


def make(old: bytes, new: bytes) -> bytes:
    ctrl_raw, diff, extra = core.diff(old, new)
    ctrl = b"".join(struct.pack(">qqq", *t) for t in unpack_control_block(ctrl_raw))
    c, d, e = zlib.compress(ctrl, 9), zlib.compress(diff, 9), zlib.compress(extra, 9)
    return MAGIC + struct.pack(">qqq", len(new), len(c), len(d)) + c + d + e


def blocks(patch: bytes):
    if patch[:8] != MAGIC:
        raise ValueError("not an XARVIS patch")
    new_size, clen, dlen = struct.unpack(">qqq", patch[8:32])
    p = 32
    ctrl = zlib.decompress(patch[p:p + clen]); p += clen
    diff = zlib.decompress(patch[p:p + dlen]); p += dlen
    extra = zlib.decompress(patch[p:])
    triples = [struct.unpack(">qqq", ctrl[i:i + 24]) for i in range(0, len(ctrl), 24)]
    return new_size, triples, diff, extra


def apply(old: bytes, patch: bytes) -> bytes:
    """Rebuilds the new file (bsdiff's own C code on the decoded blocks): proves the patch before it's published."""
    new_size, triples, diff, extra = blocks(patch)
    ctrl_raw = b"".join(core.encode_int64(v) for t in triples for v in t)
    out = core.patch(old, ctrl_raw, diff, extra)
    if len(out) != new_size:
        raise ValueError("wrong size")
    return out


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def manifest_strings(apk: bytes) -> list:
    """The strings in an APK's binary AndroidManifest.xml (its string pool)."""
    import io
    import zipfile
    data = zipfile.ZipFile(io.BytesIO(apk)).read("AndroidManifest.xml")
    pos = struct.unpack_from("<H", data, 2)[0]  # skip the XML header
    while pos + 8 <= len(data):
        ctype, header, size = struct.unpack_from("<HHI", data, pos)
        if ctype == 0x0001:  # RES_STRING_POOL_TYPE
            count, _styles, flags, start, _ = struct.unpack_from("<IIIII", data, pos + 8)
            utf8 = flags & 0x100
            out = []
            for i in range(count):
                off = pos + start + struct.unpack_from("<I", data, pos + header + 4 * i)[0]
                if utf8:
                    n = data[off]; off += 2 if n & 0x80 else 1  # length in UTF-16 units, skipped
                    n = data[off]
                    if n & 0x80:
                        n = ((n & 0x7F) << 8) | data[off + 1]; off += 2
                    else:
                        off += 1
                    out.append(data[off:off + n].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, off)[0]; off += 2
                    if n & 0x8000:
                        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, off)[0]; off += 2
                    out.append(data[off:off + 2 * n].decode("utf-16-le", "replace"))
            return out
        if size <= 0:
            break
        pos += size
    return []


def source_fingerprint(apk: bytes):
    """The build's "xarvis.src" meta-data value ("xs" + 16 hex), if it has one."""
    try:
        return next((s for s in manifest_strings(apk) if re.fullmatch(r"xs[0-9a-f]{16}", s)), None)
    except Exception as e:  # never let this stop the patches
        print(f"couldn't read the manifest: {e}", file=sys.stderr)
        return None


def gh(*args: str, out_path: str = None) -> str:
    """Runs `gh` (4 tries). With [out_path] its output (a file's bytes) goes there instead of being returned."""
    for attempt in range(4):
        if out_path:
            with open(out_path, "wb") as f:
                r = subprocess.run(["gh", *args], stdout=f, stderr=subprocess.PIPE)
            err = r.stderr.decode(errors="replace")
        else:
            r = subprocess.run(["gh", *args], capture_output=True, text=True)
            err = r.stderr
        if r.returncode == 0:
            return "" if out_path else r.stdout
        print(f"gh {' '.join(args[:3])}… failed ({err.strip()[:200]}), retrying", file=sys.stderr)
        time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"gh {' '.join(args)} failed")


def download(repo: str, release: dict, name: str, path: str) -> bytes:
    """A release asset through the REST API (works wherever `gh api` does)."""
    asset = next((a for a in release["assets"] if a["name"] == name), None)
    if asset is None:
        raise RuntimeError(f"{release['tag_name']} has no {name}")
    gh("api", "-H", "Accept: application/octet-stream", f"repos/{repo}/releases/assets/{asset['id']}", out_path=path)
    with open(path, "rb") as f:
        data = f.read()
    if len(data) != asset["size"]:
        raise RuntimeError(f"{release['tag_name']}/{name}: got {len(data)} bytes, expected {asset['size']}")
    return data


def number(tag: str):
    m = re.fullmatch(r"v1\.0\.(\d+)", tag)
    return int(m.group(1)) if m else None


def release_cmd(repo: str, tag: str, dry_run: bool = False, back: int = 0) -> None:
    target = number(tag)
    if target is None:
        raise SystemExit(f"{tag} isn't a v1.0.N release")
    releases = json.loads(gh("api", f"repos/{repo}/releases?per_page=40"))
    this = next((r for r in releases if r["tag_name"] == tag), None)
    if this is None:
        raise SystemExit(f"release {tag} not found")
    older = sorted(
        (r for r in releases if not r["draft"] and not r["prerelease"] and (number(r["tag_name"]) or 0) and number(r["tag_name"]) < target),
        key=lambda r: number(r["tag_name"]), reverse=True,
    )
    work = tempfile.mkdtemp(prefix="patches-")
    cache = {}

    def fetch(rel: dict, name: str, required: bool = False):
        key = (rel["tag_name"], name)
        if key not in cache:
            d = os.path.join(work, rel["tag_name"])
            os.makedirs(d, exist_ok=True)
            try:
                cache[key] = download(repo, rel, name, os.path.join(d, name))
            except RuntimeError as e:
                if required:
                    raise  # the new release's own files: stop, rather than publish an empty or wrong list
                print(f"skipped: {e}", file=sys.stderr)
                cache[key] = None
        return cache[key]

    version = tag[1:]
    manifest = {"version": version, "files": {}, "patches": []}
    names = {a["name"] for a in this["assets"]}
    for name in LISTED:
        if name in names:
            data = fetch(this, name, required=True)
            manifest["files"][name] = {"sha256": sha256(data), "size": len(data)}
            src = source_fingerprint(data)
            if src:
                manifest["files"][name]["src"] = src
    uploads = []
    for name, keep in PATCHED.items():
        keep = back or keep
        if name not in names:
            continue
        new = fetch(this, name, required=True)
        stem = name[:-4]
        for rel in older[:keep]:
            if name not in {a["name"] for a in rel["assets"]}:
                continue
            old = fetch(rel, name)
            if old is None:
                continue
            t = time.time()
            patch = make(old, new)
            if apply(old, patch) != new:
                print(f"{name} from {rel['tag_name']}: the patch didn't rebuild the file — skipped", file=sys.stderr)
                continue
            if len(patch) > MAX_SHARE * len(new):
                print(f"{name} from {rel['tag_name']}: {len(patch)} bytes, not worth it — skipped")
                continue
            asset = f"{stem}-{rel['tag_name']}-to-{tag}.patch"
            path = os.path.join(work, asset)
            with open(path, "wb") as f:
                f.write(patch)
            uploads.append(path)
            manifest["patches"].append({
                "file": name, "from": rel["tag_name"][1:], "fromSha256": sha256(old), "asset": asset,
                "size": len(patch), "sha256": sha256(patch),
            })
            print(f"{asset}: {len(patch) / 1e6:.2f} MB (full {len(new) / 1e6:.1f} MB) in {time.time() - t:.0f} s")
            cache.pop((rel["tag_name"], name), None)  # memory: the runner holds several 100 MB files otherwise
    path = os.path.join(work, "patches.json")
    with open(path, "w") as f:
        json.dump(manifest, f, indent=1)
    if dry_run:
        print(json.dumps(manifest, indent=1))
        return
    # The patches first, the list last: the Updater only offers a patch that is already there.
    if uploads:
        gh("release", "upload", tag, *uploads, "--repo", repo, "--clobber")
    gh("release", "upload", tag, path, "--repo", repo, "--clobber")
    print(f"patches.json: {len(manifest['patches'])} patches, {len(manifest['files'])} files")


def one_cmd(old_path: str, new_path: str, out_path: str) -> None:
    old = open(old_path, "rb").read()
    new = open(new_path, "rb").read()
    t = time.time()
    patch = make(old, new)
    assert apply(old, patch) == new, "patch doesn't rebuild the file"
    open(out_path, "wb").write(patch)
    print(f"{out_path}: {len(patch)} bytes ({len(patch) / 1e6:.2f} MB) from {len(new) / 1e6:.1f} MB in {time.time() - t:.0f} s; "
          f"new sha256 {sha256(new)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("release")
    r.add_argument("--repo", required=True)
    r.add_argument("--tag", required=True)
    r.add_argument("--dry-run", action="store_true", help="make and check the patches, upload nothing")
    r.add_argument("--back", type=int, default=0, help="patch from this many earlier releases (default: per file)")
    m = sub.add_parser("strings", help="print an APK's manifest strings (a check)")
    m.add_argument("apk")
    o = sub.add_parser("one")
    o.add_argument("old")
    o.add_argument("new")
    o.add_argument("out")
    a = ap.parse_args()
    if a.cmd == "release":
        release_cmd(a.repo, a.tag, a.dry_run, a.back)
    elif a.cmd == "strings":
        data = open(a.apk, "rb").read()
        print(manifest_strings(data))
        print("fingerprint:", source_fingerprint(data))
    else:
        one_cmd(a.old, a.new, a.out)
