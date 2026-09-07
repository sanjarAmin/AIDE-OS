# `:editor` — findings

What building the editor cost, and the constraints that are not visible from
the code. Everything here was found the hard way; none of it is documented by
the libraries involved.

The editor is [sora-editor](https://github.com/Rosemoe/sora-editor) 0.23.6 with
tree-sitter highlighting through
[`com.itsaky.androidide.treesitter`](https://github.com/AndroidIDEOfficial/android-tree-sitter)
4.3.2. Both are LGPL-2.1 and are used unmodified, as published Maven artifacts.

---

## 1. The tree-sitter core does not load itself

`TreeSitter.loadLibrary()` has to be called before anything touches a grammar.
Nothing in the API hints at it, and skipping it does not fail at the call you
would expect: the first grammar access throws `UnsatisfiedLinkError` from inside
a static initialiser, which then poisons that class for the life of the
process — every later attempt fails with a different, more confusing error
about the class being uninitialised.

`TreeSitterRuntime` does the load once and reports whether it worked, so the
rest of the module can fall back to plain text rather than crash.

## 2. Grammars are coupled to the core they were built against

There are two published families of these bindings:
`io.github.itsaky:*:1.4.x` and `com.itsaky.androidide.treesitter:*:4.3.x`.
They are the same project at different points in its life, and a grammar from
one **does not load against the other's core**. The failure is again an
`UnsatisfiedLinkError`, not a version conflict Gradle could catch, because the
JNI symbol names differ.

sora's `language-treesitter` depends on the 4.3.x family. Every grammar has to
be pinned to it, which is why they are listed individually in
`build.gradle.kts` rather than picked up transitively.

## 3. The grammars ship no queries, and upstream queries do not fit

Highlighting needs a `highlights.scm` query, and the grammar artifacts contain
none. The obvious source is the upstream `tree-sitter-<lang>` repository — and
for Java, Kotlin and JSON that works with small edits.

For **XML it does not work at all**. itsaky's XML grammar is a different grammar
from upstream's, not a different version of it: it produces `element`,
`attr_value`, `tag_start` and `xml_decl`, where upstream's query targets `PI`,
`EncName` and `VersionNum`. Nothing matches. The query in
`assets/treesitter/xml/highlights.scm` is hand-written against the symbols the
shipped grammar actually produces.

A query that names a node type the grammar does not have does not degrade — the
whole query fails to compile with `NodeType at line N: <name>`, and the file
opens unhighlighted. `TreeSitterQueryTest` compiles every query on a device for
exactly this reason, and pairs it with an assertion that the theme has no entry
no grammar produces, so a query and its colours cannot drift apart silently.

## 4. A `TsLanguageSpec` belongs to the `TsLanguage` that was given it

This one is worth reading before caching anything.

`TsLanguage.destroy()` closes its spec, and `CodeEditor.setEditorLanguage()`
destroys the language it is replacing. A spec shared between two files is
therefore closed the moment the second file is opened, and every language built
from it afterwards throws `IllegalStateException("spec is closed")`.

Caching specs by language is the obvious optimisation — grammars and compiled
queries are not cheap — and it works exactly once. `EditorLanguages` caches the
query *text* instead and builds a fresh spec per language instance.
`EditorHighlightTest.a_second_file_of_the_same_language_still_highlights`
pins it down; nothing below the widget level can see this.

## 5. A `Content` is the undo stack, and tabs live or die on that

`CodeEditor.setText(CharSequence)` builds a fresh `Content` unless it is handed
one to reuse — the three-argument overload with `reuseContentObject = true`. A
`Content` is not just the characters: it carries the undo history and the
cursor.

So a tab strip that swaps text by passing the document's `String` back in loses
both on every switch. Nothing throws and nothing is actually lost from disk,
but coming back to a file you were editing to find the cursor at the top and
Undo empty reads as data loss, which is worse. `EditorBuffers` keeps one
`Content` per open file and hands it back.

One widget serves every tab rather than one editor per tab: each `CodeEditor`
carries a parse thread and native tree-sitter memory, and a phone with eight
files open cannot afford eight of them.

## 6. An empty query is a comment, not an empty string

`TsLanguageSpec` takes four queries. The grammars ship one. The other three —
code blocks, brackets, locals — are wanted only by folding markers, bracket
matching and scope-aware highlighting, none of which is worth blocking syntax
colouring on.

They cannot be `""`: the binding rejects a blank query outright with
`IllegalArgumentException: Query cannot be null or blank`, at construction, so
no file of that language opens at all. `"; intentionally empty"` is a comment,
compiles to no patterns, and means what the empty string was meant to.

## 7. Core library desugaring is not optional

The tree-sitter AARs declare it in their AAR metadata, so the build fails at
`checkDebugAndroidTestAarMetadata` rather than at run time — the right call by
whoever set it. It has to be enabled in `:editor` **and in every module that
consumes it**, including `:app`, along with `coreLibraryDesugaring(...)`.

## 8. A BOM behind an `api` dependency has to be `api` too

`:editor` exposes sora-editor to `:app` with `api(libs.sora.editor)`, which
carries no version — the version comes from the sora BOM. With the BOM declared
`implementation`, that constraint stops at the module boundary and `:app` fails
to resolve with `Could not find io.github.Rosemoe.sora-editor:editor:` (note the
empty version). The platform has to be `api` wherever the artifact it versions
is.

---

## What is deliberately not built

- **60fps as an assertion.** The acceptance criterion says a 5,000-line file
  scrolls at 60fps. Frame timing measured on an emulator hosted by a desktop
  says nothing about a phone, and a threshold that passes everywhere is worse
  than no threshold. `EditorHighlightTest` asserts that a 5,000-line file is
  correctly highlighted end to end and leaves the frame rate to the manual
  device matrix.
- **Formatting, folding, bracket matching.** All three need queries the
  grammars do not ship (finding 6). They are worth adding per language, and are
  not worth blocking anything else on.
- **Multiple editors at once.** Split view would need more than one
  `CodeEditor` alive, which finding 5 explains is not free. Nothing needs it
  yet.
- **Diagnostics as you type.** The gutter shows what the last *build* found.
  Live diagnostics are M3 and need a language server, not more editor.

## The editor did not follow dark mode, and nothing said so

Found 2026-09-07 by putting the emulator into night mode and opening a file.
The app's chrome — bar, tabs, breadcrumb, symbol row — all followed the system,
and the editor stayed on sora's default light scheme: at night, most of the
screen was a white rectangle inside a dark app.

`EditorTheme`'s own KDoc said "colours are not chosen here … what they resolve
to is the app's theme", which was **aspirational**. `EditorTheme` maps
tree-sitter captures onto slots in sora's `EditorColorScheme`; nothing ever set
that scheme, so it stayed the light default whatever the app did.

Every test passed throughout, and would have: the highlighting tests assert
which *slot* a token gets (`EditorColorScheme.KEYWORD`), which is correct in
either scheme. A test that could see this would have to compare pixels or
assert on the scheme object, and the cheap version of the second is
`EditorColorThemeTest` — the resolution is a function now rather than an `if`
inside an `AndroidView` update block, where nothing can be asserted.

The fix is sora's own `SchemeDarcula` for dark and its default for light, chosen
per composition from `isSystemInDarkTheme()` unless the user overrode it. The
scheme is compared before being assigned: a new scheme object makes the editor
rebuild its styles, which on a 5,000-line file is visible.

## Editor settings are applied in `update`, not in the factory

Added 2026-09-07, when the four hardcoded appearance values became settings.

`CodeEditorView`'s `AndroidView` builds the widget once and reuses it for every
tab — one `CodeEditor` for the whole screen, for the reason finding 5 gives. So
anything set in the `factory` block is set **once, at first composition**, and a
value changed while a file is open never reaches the widget: the user would have
to close the tab and reopen it, which reads as the setting not working.

Text size, tab width, line numbers and word wrap are therefore set in `update`,
which runs on every recomposition. They are cheap — four setters on a view that
is already laid out — and the alternative is a setting that appears to be
ignored.

The settings themselves are a `StateFlow` on `EditorPreferences`, collected in
`WorkspaceScreen`, rather than read inside the editor. On a tablet the settings
pane and the editor are on screen together, and a read-on-composition would only
update when something else happened to recompose.

**The stored value is clamped on the way out as well as in.** A range can narrow
in a later version and the preferences file is editable on a rooted device; a
zero text size is an editor that draws nothing, which presents as the file
failing to open rather than as a setting. `EditorPreferencesTest` pins that.
