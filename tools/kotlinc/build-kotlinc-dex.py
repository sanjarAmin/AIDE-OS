#!/usr/bin/env python3
"""
Builds a dex archive containing the Kotlin compiler and the Compose compiler
plugin, loadable on Android with PathClassLoader.

Three things make this work:

1. The compiler discovers plugins through ServiceLoader. Dexing the Compose
   plugin into the same archive therefore registers it -- no `-Xplugin`, no
   URLClassLoader, neither of which can load a .jar of Java bytecode on ART.
   The archive keeps the merged META-INF/services entries alongside the dex,
   laid out like an APK.

2. Android has no java.lang.management, which the compiler touches for build
   timings and thread dumps. References are rewritten to a compat shim.
   `java/lang/management/` and `aideos/kt/management/` are both exactly 21
   characters (as are their dotted forms), so this is a byte-for-byte patch of
   the constant pool -- no class reassembly and no ASM dependency.

3. A few classes cannot be rescued by renaming and are replaced outright by a
   source file under --shim carrying the same fully-qualified name. The original
   is dropped from the jar on the way past, since d8 rejects a duplicate type.
   See REPLACED for what is replaced and why.

Usage: build-kotlinc-dex.py --jars DIR --shim DIR --out FILE [--sdk DIR]
"""

import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

REWRITES = [
    (b"java/lang/management/", b"aideos/kt/management/"),
    (b"java.lang.management.", b"aideos.kt.management."),
]

for src, dst in REWRITES:
    assert len(src) == len(dst), f"{src!r} and {dst!r} must be the same length"

# Classes the shim directory supplies in full, dropped from the jars here.
REPLACED = {
    # Resolves ten sun.misc.Unsafe methods in its static initializer and lets
    # any failure escape. ART has nine of them; the missing copyMemory therefore
    # stops ConcurrentLongObjectHashMap loading, which stops CoreProgressManager
    # loading, which stops every compilation. The shim resolves lazily and
    # emulates copyMemory with ART's pinned-array equivalents.
    "org/jetbrains/kotlin/com/intellij/util/containers/Unsafe.class",
}


def patch_jar(src: pathlib.Path, dst: pathlib.Path) -> tuple[int, int]:
    """
    Copies a jar, rewriting java.lang.management references in class files and
    dropping the classes the shim replaces.

    Returns (classes patched, classes dropped).
    """
    patched = 0
    dropped = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename in REPLACED:
                dropped += 1
                continue
            data = zin.read(item.filename)
            if item.filename.endswith(".class"):
                original = data
                for old, new in REWRITES:
                    data = data.replace(old, new)
                if data != original:
                    patched += 1
            zout.writestr(item, data)
    return patched, dropped


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", required=True, help="directory of compiler jars")
    parser.add_argument("--shim", required=True, help="directory of compiled shim classes")
    parser.add_argument("--out", required=True, help="output archive")
    parser.add_argument("--sdk", default=os.path.expanduser("~/Android/Sdk"))
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--android-jar", default=None)
    parser.add_argument("--min-api", default="30")
    parser.add_argument("--java", default="/opt/android-studio/jbr/bin/java")
    args = parser.parse_args()

    jars_dir = pathlib.Path(args.jars)
    jars = sorted(jars_dir.glob("*.jar"))
    # Only the compiler stack belongs in the archive; the compile-time classpath
    # jars (stdlib for user code, compose runtime) are passed to the compiler at
    # run time instead, and must not be dexed into it.
    jars = [j for j in jars if j.name != "compose-runtime.jar"]
    if not jars:
        print(f"no jars found in {jars_dir}", file=sys.stderr)
        return 1

    android_jar = args.android_jar or os.path.join(
        args.sdk, "platforms", "android-37.0", "android.jar"
    )
    d8_jar = os.path.join(args.sdk, "build-tools", args.build_tools, "lib", "d8.jar")

    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        patched_dir = tmp / "patched"
        patched_dir.mkdir()

        total = 0
        total_dropped = 0
        for jar in jars:
            n, d = patch_jar(jar, patched_dir / jar.name)
            total += n
            total_dropped += d
            print(f"  {jar.name}: {n} classes patched, {d} dropped")
        print(f"rewrote java.lang.management in {total} classes")

        # A silent zero here means the shim now defines a class nothing removed,
        # and d8 would fail on the duplicate several minutes from now.
        if total_dropped != len(REPLACED):
            print(
                f"expected to drop {len(REPLACED)} replaced classes, dropped {total_dropped}",
                file=sys.stderr,
            )
            return 1

        dex_dir = tmp / "dex"
        dex_dir.mkdir()
        cmd = [
            args.java, "-Xmx8g", "-cp", d8_jar, "com.android.tools.r8.D8",
            "--release", "--min-api", args.min_api,
            "--lib", android_jar,
            "--output", str(dex_dir),
            *[str(p) for p in sorted(patched_dir.glob("*.jar"))],
        ]
        # The shim supplies the rewritten java.lang.management target package.
        cmd += [str(p) for p in sorted(pathlib.Path(args.shim).rglob("*.class"))]
        print("running d8...")
        result = subprocess.run(cmd)
        if result.returncode != 0:
            return result.returncode

        stage = tmp / "stage"
        stage.mkdir()
        for dex in dex_dir.glob("*.dex"):
            shutil.copy2(dex, stage / dex.name)

        # ServiceLoader reads these; dex files carry no resources.
        services = stage / "META-INF" / "services"
        services.mkdir(parents=True)
        merged = {}
        for jar in jars:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if name.startswith("META-INF/services/") and not name.endswith("/"):
                        # Keep the whole relative path: jline nests its providers
                        # under services/org/jline/..., and keying on the last
                        # segment alone would file them as "exec" and "jna".
                        key = name[len("META-INF/services/"):]
                        merged.setdefault(key, []).append(z.read(name).decode())
        for key, bodies in merged.items():
            # Several jars can contribute providers for the same extension point.
            lines = []
            for body in bodies:
                lines.extend(l for l in body.splitlines() if l.strip())
            target = services / key
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("\n".join(dict.fromkeys(lines)) + "\n")
        print(f"merged {len(merged)} service descriptors")

        # d8 consumes the code and discards everything else, but the compiler
        # reads a great deal of itself back as resources: its version stamp, its
        # extension point config, the serialized builtins, the .kotlin_module
        # files. Each one missing is a separate obscure crash on startup, so
        # rather than name them, every non-class entry travels alongside the dex
        # -- which is all a jar was doing for them anyway.
        skip_exact = {"META-INF/MANIFEST.MF", "META-INF/INDEX.LIST"}
        # Signatures describe jars that no longer exist by the time we are done.
        skip_suffix = (".SF", ".RSA", ".DSA", ".EC")
        carried = 0
        conflicts = []
        for jar in jars:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if name.endswith("/") or name.endswith(".class"):
                        continue
                    # Merged above, into a different layout.
                    if name.startswith("META-INF/services/"):
                        continue
                    if name in skip_exact or name.endswith(skip_suffix):
                        continue
                    body = z.read(name)
                    target = stage / name
                    if target.exists():
                        # Flattening a classpath into one archive turns two
                        # same-named entries into one. A classloader would have
                        # returned the first jar's too, so keep that and say so
                        # rather than pretend the collision did not happen.
                        if target.read_bytes() != body:
                            conflicts.append(f"{name} (also in {jar.name})")
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(body)
                    carried += 1

        # On startup the compiler asks where it is installed, and answers by
        # looking up one of its own classes *as a resource* and taking the
        # archive that serves it. That is the one .class the dex cannot replace:
        # without it the lookup finds nothing and startup dies in
        # registerApplicationExtensionPointsAndExtensionsFrom. Carried as a
        # resource and never loaded from, it makes the archive answer with its
        # own path -- which is the true answer.
        anchor = "org/jetbrains/kotlin/cli/common/CompilerSystemProperties.class"
        for jar in jars:
            with zipfile.ZipFile(jar) as z:
                if anchor in z.namelist():
                    target = stage / anchor
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(z.read(anchor))
                    carried += 1
                    break
        else:
            print(f"{anchor} was not found in any jar", file=sys.stderr)
            return 1
        print(f"carried {carried} resources")
        for conflict in conflicts:
            print(f"  kept the first of two differing copies of {conflict}")

        out = pathlib.Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.exists():
            out.unlink()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
            for path in sorted(stage.rglob("*")):
                if path.is_file():
                    z.write(path, path.relative_to(stage).as_posix())
        print(f"wrote {out} ({out.stat().st_size // (1024 * 1024)} MB)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
