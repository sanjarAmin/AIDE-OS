# Highlight queries

Tree-sitter grammars ship as compiled native libraries and carry no queries, so
the `highlights.scm` files here come from each grammar's own repository and are
vendored unmodified:

| Directory | Source | Licence |
|---|---|---|
| `java/` | [tree-sitter/tree-sitter-java](https://github.com/tree-sitter/tree-sitter-java) | MIT |
| `kotlin/` | [fwcd/tree-sitter-kotlin](https://github.com/fwcd/tree-sitter-kotlin) | MIT |
| `xml/` | [tree-sitter-grammars/tree-sitter-xml](https://github.com/tree-sitter-grammars/tree-sitter-xml) | MIT |
| `json/` | [tree-sitter/tree-sitter-json](https://github.com/tree-sitter/tree-sitter-json) | MIT |
| `python/` | [tree-sitter/tree-sitter-python](https://github.com/tree-sitter/tree-sitter-python) v0.23.6 | MIT |
| `javascript/` | [tree-sitter/tree-sitter-javascript](https://github.com/tree-sitter/tree-sitter-javascript) v0.23.1 | MIT |
| `javascriptx/` | the same, `highlights.scm` + `highlights-jsx.scm` concatenated | MIT |
| `c/` | [tree-sitter/tree-sitter-c](https://github.com/tree-sitter/tree-sitter-c) v0.23.4 | MIT |
| `cpp/` | tree-sitter-c v0.23.4 + [tree-sitter/tree-sitter-cpp](https://github.com/tree-sitter/tree-sitter-cpp) v0.23.4, concatenated in that order | MIT |

## The Kotlin query is edited; the rest are not

`kotlin/highlights.scm` names four nodes the prebuilt grammar does not have --
`null_literal` and the three string-interpolation delimiters -- so the whole
query failed to compile and Kotlin rendered as plain text. Those patterns are
removed, each marked `; AIDE-OS:` in place with what it cost. The grammar is an
older revision of fwcd's than the query targets; the fix when that is no longer
true is to re-vendor the query and delete the marks.

## JavaScript's grammar is ours, and its query is pinned to it

Every grammar here is `com.itsaky.androidide.treesitter`'s prebuilt one except
JavaScript, which that publisher does not ship at all. `tools/treesitter/`
builds it from tree-sitter-javascript v0.23.1 and copies **that tag's**
`highlights.scm` here, so the pair moves together — which is the one case on
this page where the version mismatch §1 describes cannot happen by accident.

`.jsx` is a separate entry using the **same grammar** and a longer query.
Upstream's `highlights-jsx.scm` is written to be applied on top of
`highlights.scm`, and tree-sitter applies one query per language, so the build
script concatenates them into `javascriptx/`. Adding `jsx` to JavaScript's
extensions instead would highlight those files with the query that has no idea
what a tag is.

A query is written against a particular revision of its grammar, and the
grammars here are `com.itsaky.androidide.treesitter`'s prebuilt ones. A query
naming a node the compiled grammar does not have fails to compile, and
tree-sitter reports it as an offset into the query with no other context.
`TreeSitterQueryTest` compiles every one of these against the grammar it belongs
to on a device, so that mismatch is a test failure rather than a language that
silently renders as plain text.

## C++'s query is two files, C's first

tree-sitter-cpp's `highlights.scm` is not a complete query. Its
`tree-sitter.json` lists its highlights as tree-sitter-c's file **and then**
its own, and on its own it colours no comments, no strings and almost no
keywords -- a C++ file would have compiled, passed the capture test, and looked
nearly plain. `cpp/highlights.scm` is the two concatenated in that order, the
same move as `javascriptx/`.

The grammars are `com.itsaky.androidide.treesitter`'s 4.3.2 builds, and the
queries were matched to them before anything ran on a device: every node name,
field name and literal in a candidate query was looked up in the grammar's
`.so`, where the symbol table stores them as NUL-terminated strings. Every C
query from v0.21.0 to v0.24.2 matches the prebuilt C grammar; every C++ query
from v0.20.2 to v0.23.4 matches the C++ one when layered on C's. v0.23.4 was
taken for both, to match the Python and JavaScript pins. Run the C++ query
against the C grammar and the same check reports 33 names missing, which is
what says the check can fail.

`.h` belongs to C++: C's grammar stops colouring at the first `class` or
`namespace`, while C++'s reads a C header with almost no loss.

