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
| `javascript/` | [tree-sitter/tree-sitter-javascript](https://github.com/tree-sitter/tree-sitter-javascript) v0.23.1 | MIT |

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

`highlights-jsx.scm` is deliberately not vendored: `EditorLanguage` claims
`.js`, `.mjs` and `.cjs` and not `.jsx`, because highlighting JSX without its
query renders every tag as an error.

A query is written against a particular revision of its grammar, and the
grammars here are `com.itsaky.androidide.treesitter`'s prebuilt ones. A query
naming a node the compiled grammar does not have fails to compile, and
tree-sitter reports it as an offset into the query with no other context.
`TreeSitterQueryTest` compiles every one of these against the grammar it belongs
to on a device, so that mismatch is a test failure rather than a language that
silently renders as plain text.
