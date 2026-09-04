#!/usr/bin/env python3
"""
Builds the top-level-callable name index the Analysis API will not build.

**Why this exists.** Completion needs to offer extensions -- `uppercase`,
`isBlank`, `map` -- and those are top-level callables in a *binary* library.
The Analysis API can resolve one if you name it
(`findTopLevelCallables(FqName, Name)` works, and applicability filtering with
it is correct), but nothing in the API will *list* them. Three routes were
tried and all return nothing for a library module: the star-importing scope,
`findPackage().packageScope`, and `KotlinDeclarationProvider`. FINDINGS.md
section 16 and 20.

So the names have to come from the jar, and the jar is ours. Kotlin compiles
top-level functions into per-file *facade* classes -- everything in
`kotlin/text/Strings.kt` becomes a static method of `kotlin.text.StringsKt`,
which for large files is a multifile facade with `StringsKt__StringsKt`-style
parts beside it. Their public static methods are the top-level callables.

Done at build time rather than on device for the reason `jars.lock` exists:
this is derived from a pinned artifact, so it should be produced once,
reproducibly, and shipped -- not recomputed on every phone that opens a Kotlin
file.

The class files are parsed here rather than shelled out to `javap` because
there are several hundred of them and the parse needed is small: the constant
pool, then the method table's name indices.

Usage: build-name-index.py <jar> [jar...] > top-level-callables.index
Format: one line per package, `package<TAB>name name name`, both sorted.
"""

import re
import struct
import sys
import zipfile
from collections import defaultdict

# Constant pool tags whose entries are a fixed number of bytes after the tag.
FIXED = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
         12: 4, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
# Long and Double take two constant pool slots. This is the single most
# commonly forgotten rule in the class file format and it silently shifts
# every index after it.
WIDE = {5, 6}

ACC_PUBLIC, ACC_PRIVATE, ACC_STATIC, ACC_SYNTHETIC = 0x0001, 0x0002, 0x0008, 0x1000

# **`@InlineOnly` functions are private JVM methods, and they are not private.**
# `println`, `print`, `let`, `also`, `require` -- a great deal of what a Kotlin
# user types -- are declared `@kotlin.internal.InlineOnly`, which the compiler
# lowers to ACC_PRIVATE so nothing can call them from Java. Filtering on
# ACC_PUBLIC alone therefore drops them, and the symptom is a completion list
# with no `println` in it. The annotation survives into the class file as a
# RuntimeInvisibleAnnotations attribute, so it can be recovered here.
#
# This is the clearest argument for reading `@kotlin.Metadata` instead, which
# records the *Kotlin* declaration rather than its JVM lowering and would need
# no special case at all. FINDINGS.md section 20.
INLINE_ONLY = "Lkotlin/internal/InlineOnly;"
ANNOTATIONS = ("RuntimeInvisibleAnnotations", "RuntimeVisibleAnnotations")

# A Kotlin file facade, or one of a multifile facade's parts.
FACADE = re.compile(r"(?:^|/)[A-Za-z0-9_]+Kt(?:__[A-Za-z0-9_]+)?\.class$")


def read_pool(data, offset):
    """The constant pool as a list, 1-based, with Utf8 entries decoded."""
    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + length].decode("utf-8", "replace")
            offset += length
        elif tag == 15:
            offset += 3
        elif tag in FIXED:
            offset += FIXED[tag]
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 2 if tag in WIDE else 1
    return pool, offset


def skip_attributes(data, offset):
    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    for _ in range(count):
        length = struct.unpack_from(">I", data, offset + 2)[0]
        offset += 6 + length
    return offset


def read_attributes(data, offset, pool, wanted_index):
    """Skip a member's attributes, reporting whether [wanted_index] is annotated.

    The check is deliberately crude: an annotation attribute is scanned for the
    constant pool index of the annotation's type, rather than decoding the
    element-value pairs. `@InlineOnly` has no arguments, so there is nothing to
    decode, and a full element_value parser is a lot of format for one bit.
    """
    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    annotated = False
    for _ in range(count):
        name_index = struct.unpack_from(">H", data, offset)[0]
        length = struct.unpack_from(">I", data, offset + 2)[0]
        body = data[offset + 6:offset + 6 + length]
        if wanted_index is not None and pool[name_index] in ANNOTATIONS:
            target = struct.pack(">H", wanted_index)
            if target in body:
                annotated = True
        offset += 6 + length
    return offset, annotated


def skip_members(data, offset):
    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    for _ in range(count):
        offset = skip_attributes(data, offset + 6)
    return offset


def method_names(data):
    """Top-level function names: public statics, plus `@InlineOnly` privates."""
    pool, offset = read_pool(data, 8)
    inline_only = next(
        (i for i, entry in enumerate(pool) if entry == INLINE_ONLY),
        None,
    )
    offset += 6                                   # access, this, super
    interfaces = struct.unpack_from(">H", data, offset)[0]
    offset += 2 + 2 * interfaces
    offset = skip_members(data, offset)           # fields

    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    names = []
    for _ in range(count):
        access, name_index = struct.unpack_from(">HH", data, offset)
        offset, annotated = read_attributes(data, offset + 6, pool, inline_only)
        if not (access & ACC_STATIC) or (access & ACC_SYNTHETIC):
            continue
        # Public, or private-because-inline-only. Anything else genuinely is a
        # helper the user cannot call.
        if not (access & ACC_PUBLIC) and not (access & ACC_PRIVATE and annotated):
            continue
        name = pool[name_index]
        # `$` catches synthetic and internal-name-mangled members; `-` catches
        # the inline-class mangling (`foo-impl`), which is not callable source.
        if not name or "$" in name or "-" in name or name.startswith("<"):
            continue
        names.append(name)
    return names


def main(argv):
    if len(argv) < 2:
        raise SystemExit("usage: build-name-index.py <jar> [jar...]")

    packages = defaultdict(set)
    for path in argv[1:]:
        with zipfile.ZipFile(path) as jar:
            for entry in jar.namelist():
                if not FACADE.search(entry):
                    continue
                package = entry.rsplit("/", 1)[0].replace("/", ".") if "/" in entry else ""
                try:
                    packages[package].update(method_names(jar.read(entry)))
                except Exception as failure:            # noqa: BLE001
                    # One unparseable class must not lose the whole index; it
                    # costs the names in that file and nothing else.
                    print(f"  ! {entry}: {failure}", file=sys.stderr)

    total = 0
    for package in sorted(packages):
        names = sorted(packages[package])
        total += len(names)
        print(f"{package}\t{' '.join(names)}")
    print(f"indexed {total} names across {len(packages)} packages", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv)
