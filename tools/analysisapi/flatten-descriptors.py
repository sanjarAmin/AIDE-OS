#!/usr/bin/env python3
"""
Inlines the `xi:include`s in the Analysis API's plugin descriptors.

**Why this exists.** The descriptors are a tree:

    analysis-api-fir-standalone-base.xml
      -> analysis-api-fir.xml
           -> analysis-api-impl-base.xml
                -> /META-INF/extensions/compiler.xml      (the compiler's own)
                -> analysis-api-platform-interface.xml
           -> low-level-api-fir.xml
           -> symbol-light-classes.xml

and the IntelliJ platform shaded into `kotlin-compiler-embeddable` resolves the
first four and then silently stops. Logging every resource lookup during the
failure shows `low-level-api-fir.xml` and `symbol-light-classes.xml` are **never
requested** -- the resolver gives up rather than asking and being refused. Those
two are the ones that live in a *different jar* from the file including them.

The error does not say any of this. It reports:

    Cannot resolve /META-INF/analysis-api/analysis-api-fir.xml

naming the file being *read*, not the include that failed -- which sends you
looking at a file that loaded perfectly well.

Rather than reverse-engineer the shaded resolver's cross-jar rules, this removes
the question: every include is substituted for its target's content at build
time, so at runtime there is nothing left to resolve.

XInclude here is IntelliJ's narrow dialect, not the general standard: an
`<xi:include href="/path"/>` inside `<idea-plugin>` means "insert that file's
`<idea-plugin>` children in my place".

Usage: flatten-descriptors.py <out-dir> <jar> [jar...]
"""

import re
import sys
import zipfile
from pathlib import Path

INCLUDE = re.compile(r'<xi:include\s+href="([^"]+)"\s*/>')

# The root element's opening and closing tags, so children can be lifted out of
# an included file without carrying a second <idea-plugin> into the result.
ROOT_OPEN = re.compile(r"^\s*<idea-plugin[^>]*>", re.S)
ROOT_CLOSE = re.compile(r"</idea-plugin>\s*$", re.S)


def read_all(jars):
    """Every XML resource in the jars, keyed by its resource path."""
    found = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith(".xml") and (
                    name.startswith("META-INF/analysis-api/")
                    or name.startswith("META-INF/extensions/")
                ):
                    found[name] = z.read(name).decode("utf-8")
    return found


def children_of(xml):
    """The body of an <idea-plugin>, without its own root tags."""
    body = ROOT_OPEN.sub("", xml, count=1)
    body = ROOT_CLOSE.sub("", body, count=1)
    return body.strip()


def flatten(path, sources, seen):
    """Expand one descriptor, recursively, guarding against cycles."""
    if path in seen:
        # Not expected in this tree, but a cycle here would be an infinite
        # file rather than an error, which is a much worse way to find out.
        raise SystemExit(f"cycle through {path}")
    seen = seen | {path}

    xml = sources[path]

    def substitute(match):
        # href is absolute-from-the-classpath-root; resources are not.
        target = match.group(1).lstrip("/")
        if target not in sources:
            # Left as-is deliberately: an include we cannot see is one the
            # platform may still resolve, and silently deleting it would
            # unregister whatever it carried.
            print(f"    ! leaving unresolved include {target}", file=sys.stderr)
            return match.group(0)
        return (
            f"<!-- inlined from {target} -->\n"
            + children_of(flatten(target, sources, seen))
        )

    return INCLUDE.sub(substitute, xml)


def main(argv):
    if len(argv) < 3:
        raise SystemExit("usage: flatten-descriptors.py <out-dir> <jar> [jar...]")

    out = Path(argv[1])
    sources = read_all(argv[2:])
    if not sources:
        raise SystemExit("no descriptors found in the given jars")

    written = 0
    for path in sorted(sources):
        if not path.startswith("META-INF/analysis-api/"):
            continue  # the compiler's own descriptors stay where they are
        flattened = flatten(path, sources, frozenset())
        remaining = len(INCLUDE.findall(flattened))
        target = out / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(flattened, encoding="utf-8")
        print(f"  {path.split('/')[-1]}: {len(flattened)} bytes, {remaining} includes left")
        written += 1

    print(f"flattened {written} descriptors into {out}")


if __name__ == "__main__":
    main(sys.argv)
