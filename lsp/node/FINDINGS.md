# `:lsp:node` — what `node --check` is and is not

Established 2026-09-07, when JavaScript had highlighting and a run button and
still no diagnostics.

## 1. Node ships no language server, and `--check` is not a substitute for one

It is a *parser*. It reports the errors V8 raises before executing a file and
nothing else, so:

| asked | answered |
|---|---|
| `function f( {` | `SyntaxError: Unexpected identifier` at 3:10 |
| `const a = 1; let a = 2;` | `SyntaxError: Identifier 'a' has already been declared` |
| `console.log(notDefinedAnywhere)` | **nothing** |

The third line is the contract, not a gap to be closed later.
`it_says_nothing_about_a_name_that_does_not_exist` asserts the silence, because
otherwise the absence reads as a bug in the wiring rather than as the boundary
of what a parser knows.

Redeclaration being caught is the pleasant surprise: it is an *early error*, so
it is on the parser's side of the line even though nothing is executed.

## 2. `complete`, `definition` and `signatureAt` answer nothing, deliberately

`LanguageService` says every method may return nothing and that nothing is an
ordinary answer. This is the first implementation that takes it up on all
three. The alternative — a keyword list and a regex for `function foo` — would
make the editor look as though it understood the file, and the first wrong
proposal would be indistinguishable from a broken index.

## 3. The output's three useful lines are not adjacent

```
/data/user/0/com.osamu.aide/cache/check-index.js:3
  return a;
         ^

SyntaxError: Unexpected identifier 'a'
    at wrapSafe (node:internal/modules/cjs/loader:1866:18)
```

- the **path and line** are the first line, and the path may contain colons, so
  the pattern anchors on the end (`^.+:(\d+)$`) rather than splitting on `:`
- the **column is the offset of the caret**, two lines below the header — not
  one, not three
- the **message** is the first line after the blank, matched by error name.
  Node's own stack frames also end in `:line:column` and satisfy any looser
  header pattern; `node_s_own_stack_frames_are_not_mistaken_for_the_error`
  is what would catch that.

The path node prints is a **scratch copy of the buffer**, so the diagnostic is
reported against the user's file instead. Reporting what node said would put a
diagnostic on a file the editor has no tab for.

`NodeSyntaxCheckTest`'s fixtures are captured output, not invented. A parser
tested against output somebody imagined is tested against nothing.

## 4. A process per keystroke, which the other three services avoid

`:lsp:java` holds a warm javac, `:lsp:kotlin` a resident Analysis API session,
`:lsp:native` a running clangd. This starts a process per check, which is
affordable only because `--check` parses and stops, and because the editor
debounces. Two consequences:

- `close()` has nothing to release, unlike every other service.
- the check runs inside `runInterruptible`, not `withContext`. A blocked read
  ignores coroutine cancellation, and the answer about the character before
  this one is worthless the moment another arrives — its thread should not
  still be held.

**This is not the shape to copy** if a real JavaScript server ever arrives.
It is the shape that fits a tool which exits.
