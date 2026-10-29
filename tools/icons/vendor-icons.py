#!/usr/bin/env python3
"""Vendor the Material icons the code actually uses, and no others.

`material-icons-extended` was a dependency of :core:ui and :ai:ui for about
57 icons out of the 2,163 it defines, and it was the largest single thing in
the APK: 13.7 MB of the 60.7 MB of dex, 23 %. R8 would strip the unused ones,
but only from a minified build, and the previews people install are debug
builds -- so the cost was paid on every download.

Each icon in that library is one generated Kotlin file declaring an extension
property such as `Icons.Filled.Terminal` in a package such as
`androidx.compose.material.icons.filled`. Copied into :core:ui under the same
package, they resolve exactly as before: no call site changes, and the
drawing is the same bytes. `material-icons-core` stays as a dependency and
provides the 56 common ones plus `materialIcon` / `materialPath`, which the
copies call.

Run this after using an icon that does not compile ("Unresolved reference")
or after removing the last use of one:

    python3 tools/icons/vendor-icons.py

It scans every module's Kotlin for `Icons.<style>.<Name>`, skips what
material-icons-core provides, copies the rest byte-identical from the
sources jar -- downloaded and checked against the pinned SHA-256 -- and
deletes vendored icons nothing uses any more. Apache-2.0, AOSP; each file
keeps its licence header. See PROVENANCE.md beside the copies.
"""

import hashlib
import io
import pathlib
import re
import sys
import urllib.request
import zipfile

VERSION = "1.7.8"
MAVEN = "https://dl.google.com/android/maven2/androidx/compose/material"
JARS = {
    "extended": (
        f"{MAVEN}/material-icons-extended-android/{VERSION}/material-icons-extended-android-{VERSION}-sources.jar",
        "77b7e782a519a3e074fc91e09d59334ac1b815edd87d2e89c0c9ca7c95678e93",
    ),
    "core": (
        f"{MAVEN}/material-icons-core-android/{VERSION}/material-icons-core-android-{VERSION}-sources.jar",
        "14db888d2f698178799a4c18a58bb134b3e98063c6748c443b339044e0145432",
    ),
}

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEST = ROOT / "core/ui/src/main/java/androidx/compose/material/icons"
# **The import, not the call site, is what compilation needs.** An icon is an
# extension property, so `Icons.Default.Terminal` only resolves through
# `import androidx.compose.material.icons.filled.Terminal` -- and an import of
# an icon nobody draws fails to compile just the same. Matching the qualified
# name catches imports and the rare fully qualified use alike; matching
# `Icons.X.Y` missed WorkspaceScreen's unused import of InsertDriveFile.
USE = re.compile(r"androidx\.compose\.material\.icons\.((?:automirrored\.)?(?:filled|outlined|rounded|sharp|twotone))\.([A-Z][A-Za-z0-9_]*)")
SOURCE_PREFIX = "commonMain/androidx/compose/material/icons/"


def fetch(kind: str) -> zipfile.ZipFile:
    url, expected = JARS[kind]
    with urllib.request.urlopen(url) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        sys.exit(f"{kind} sources jar is not the pinned one:\n  {url}\n  expected {expected}\n  got      {actual}")
    return zipfile.ZipFile(io.BytesIO(data))


def used_icons() -> set[str]:
    """Paths relative to the icons package, e.g. `filled/Terminal.kt`."""
    found = set()
    for path in ROOT.rglob("*.kt"):
        parts = path.relative_to(ROOT).parts
        if "build" in parts or path.is_relative_to(DEST):
            continue
        for package, name in USE.findall(path.read_text(encoding="utf-8", errors="replace")):
            found.add(package.replace(".", "/") + f"/{name}.kt")
    return found


def main() -> None:
    extended = fetch("extended")
    core = fetch("core")
    provided = {n[len(SOURCE_PREFIX):] for n in core.namelist() if n.startswith(SOURCE_PREFIX)}
    available = {n[len(SOURCE_PREFIX):] for n in extended.namelist() if n.startswith(SOURCE_PREFIX)}

    wanted = sorted(used_icons() - provided)
    unknown = [icon for icon in wanted if icon not in available]
    if unknown:
        sys.exit("Used but in neither icon library -- a typo, or a newer icon than " + VERSION + ":\n  " + "\n  ".join(unknown))

    present = {p.relative_to(DEST).as_posix() for p in DEST.rglob("*.kt")} if DEST.exists() else set()
    for stale in sorted(present - set(wanted)):
        (DEST / stale).unlink()
        print(f"removed  {stale}")
    for icon in wanted:
        target = DEST / icon
        data = extended.read(SOURCE_PREFIX + icon)
        if not target.exists() or target.read_bytes() != data:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            print(f"vendored {icon}")
    print(f"{len(wanted)} vendored, {len(used_icons() & provided)} from material-icons-core")


if __name__ == "__main__":
    main()
