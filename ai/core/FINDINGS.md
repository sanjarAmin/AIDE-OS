# M5: the AI layer — what was not obvious

Spike R5 (`tools/ai/FINDINGS.md`) settled the platform question: the Anthropic
SDK runs on ART unmodified. Everything below is what building on top of it cost,
and almost none of it is about Android. The theme is different from earlier
milestones: **M5's characteristic bug produces a correct answer.** A broken
cache still answers. A dropped tool result still answers, until it does not. An
absolute path in a prompt still gets a reply, one round trip later. There is
usually nothing to catch and nothing wrong on screen, which is why each of these
has a test that fails when the line is removed.

Measured on `aideos_test` (API 34, x86_64) unless stated otherwise.

---

## 1. Koin cannot hold `null` in a singleton — and it took the app down

The worst bug of the milestone was not in the AI layer at all. It had been in
`:app` since M4:

```kotlin
single<KotlinCompiler?> { provider.toolchain()?.let { KotlinCompiler(it, dir) } }
```

On a device without the Kotlin toolchain installed, `toolchain()` returns null,
the definition resolves to null, and Koin throws:

```
IllegalStateException: Single instance created couldn't return value
```

`ProjectBuilder` depends on it, so **opening any project crashed the app**. Not
an edge case: the compiler is a 54 MB download the app fetches on demand, so
"not installed" is the state every new user is in.

It survived a green suite because the null branch is the *uncommon* one on a
development machine — the toolchain is installed there — and every unit test
builds its objects by hand rather than through the graph. Nothing ever resolved
`ProjectBuilder` from `appModule` in a test.

**The fix is a holder, not a nullable definition.** `KotlinCompilerSource`
resolves on use (so a toolchain installed in-app is picked up by the next build
rather than the next launch) and memoises only success, since re-checking is a
stat call and the compiler's ~11 s startup is not.

**The test that was missing** is `AppModuleTest`: resolve every definition a
workspace needs, from the real module, on a bare device. Add to it whenever
something joins the graph — the second thing it caught was `AssistantViewModel`,
which pulls in both the builder and the repository.

---

## 2. Thinking blocks must be replayed **unchanged**, or the *next* request fails

Adaptive thinking means real responses carry `thinking` blocks, including in
front of tool calls. The API requires them back verbatim on the following turn;
removing or rebuilding them produces a block-ordering or signature error that
names nothing useful.

The trap is that the obvious implementation looks right. Reconstructing the
assistant's turn from `response.textOnly()` produces a valid-looking message,
passes every local assertion, and fails on the request *after* the one you are
debugging.

```kotlin
messages += response.toParam()   // right: the whole turn, blocks intact
messages += assistantTurn(text)  // wrong: silently drops thinking and tool_use
```

`Message.toParam()` is the round trip. `AiSessionTest`'s fixture emits a
thinking block with a signature specifically so a session that drops them fails
here rather than against the real endpoint.

---

## 3. Parallel tool results go in **one** user message

An assistant turn can hold several `tool_use` blocks. All of their
`tool_result`s belong in a single user message. Splitting them across messages
raises no error and returns a fine answer — the model simply stops asking for
parallel calls, and the assistant gets quietly slower over the conversation.

Related and sharper: **every `tool_use` needs a matching `tool_result`,
including refused ones.** A missing result is a 400. A tool the user declined is
a result with `is_error: true` — which is also information the model can act on,
where a hole is not.

---

## 4. The SDK builder treats `messages` as required

`MessageCreateParams.builder()` throws on `build()` if `messages` was never set,
even when the conversation is legitimately empty:

```
IllegalStateException: `messages` is required, but was not set
```

Set `.messages(emptyList())` explicitly. An empty conversation is still invalid
to the API, but that is the API's judgement to make with an error naming the
problem, rather than a builder exception raised while the caller is looking at
prompt layout.

---

## 5. Prompt caching is a layout, and its failure mode is a bill

Caching is prefix-match, and the request renders `tools` → `system` →
`messages`. Anything that changes invalidates everything after it. The layout
`PromptAssembler` enforces:

1. **Tools** — fixed set, fixed order. Reordering them costs every conversation.
2. **Standing instructions** — no cache control on this block.
3. **Project context** — the cache breakpoint sits here.
4. **Conversation** — everything volatile, after the last breakpoint.

Two system blocks rather than one concatenated block, because the breakpoint
marks a boundary: sharing an entry between the instructions and the project
context makes every project change re-cache text that never varies.

A timestamp, a cursor position, or a "you are helping with X.kt" line anywhere
above step 4 costs the whole prefix and **returns a perfectly good answer**.
`usage.cacheReadInputTokens` is the only way to see it. Since that number needs
a real key, `PromptAssemblerTest` pins the property that makes a hit possible
instead: two turns of one conversation must have a byte-identical cacheable
prefix.

A corollary that is easy to get wrong later: contributed tools
(`ProjectToolset(files, extra)`) must be built **once per session**. A list
assembled per turn is a new prefix every turn.

---

## 6. Anything handed to the model must carry **relative** paths

`ProjectFiles.resolve` refuses absolute paths by design, so the model learns to
send relative ones. The consequence runs the other way too: any text put into a
prompt — a diagnostic in a "fix this" request, an error in a build summary —
must already be relative, or the model's first tool call is a refusal and its
second is a guess at the right form. The only symptom is an assistant that seems
slow and confused.

A path that escapes the project (`../..`) is refused for the same reason, so
those degrade to the bare filename: something to search for beats something that
cannot be read.

---

## 7. Testing an agent loop: three things that cost an hour each

**`advanceUntilIdle()` does not wait for `Dispatchers.IO`.** The session's
request goes through `withContext(dispatchers.io)`; with a real IO dispatcher
the HTTP call lands on a thread the test clock knows nothing about, so
`advanceUntilIdle()` returns mid-turn and every assertion reads a half-finished
state. Pin IO to `StandardTestDispatcher(testScheduler)`.

**`runTest` waits for every child coroutine.** A test that deliberately leaves
the approval handshake parked — which is the point of the test — ends in a
60-second timeout rather than a pass. Release it after the assertions.

**A local Messages API has to be *scripted*.** The interesting request is the
second one, the one carrying tool results, so a fixture that returns the same
body every time cannot test the loop at all. `ScriptedApi` serves a queue and
records every request body; the assertions read the bodies.

`com.sun.net.httpserver` is still absent on Android — see
`tools/ai/FINDINGS.md` §1 — so this is MockWebServer, and `androidTest` needs a
loopback-scoped `network-security-config` or the SDK reports
`AnthropicIoException: Request failed` three frames above the real cause.

---

## 8. Compose: two nodes with the same label break unrelated tests

The chat panel's empty-state heading was "Ask about this project", which is also
the composer's placeholder. `onNodeWithText(...).assertExists()` fails on
multiple matches, and `performTextInput` on an ambiguous node fails too — so a
new empty-state test broke an existing send test that had nothing to do with it.

Worth recording because the test failure is the *lucky* outcome: the same
duplication is ambiguous to a screen reader, and nothing else would have said
so.

---

## 9. A custom endpoint is one builder call and four rejections

`AnthropicOkHttpClient.baseUrl` is all it takes to point this SDK at anything
that speaks the same wire format. The work is not the wiring — it is that every
way of mistyping a URL fails at *request* time, several turns later, with an
error that blames the SDK.

**The SDK appends `/v1/messages`.** Pinned by
`EndpointTest.the_sdk_appends_v1_messages_to_the_base_url` rather than assumed,
because two silent corrections in `parseEndpoint` depend on it:

- A **trailing slash** yields `//v1/messages`. `ScriptedApi` has trimmed one off
  since the first test in this module, which is how easy it is to hit.
- A **trailing `/v1`** yields `/v1/v1/messages` and a 404. This is the likelier
  mistake, because `/v1/messages` is what every provider's documentation shows,
  so the URL gets copied down to the version. Safe to strip rather than warn
  about: a proxy mounted at `/anything/v1/messages` has base `/anything`, so no
  correct base URL for this SDK ends in `/v1`.

Both corrections are **shown, not just applied** — the field is rewritten with
what was stored. A silent fix plus the original text left on screen leaves the
user believing something other than what is saved.

**`http` is refused, and the reason is about the key rather than about URLs.**
The API key travels as a request header, so cleartext puts a billable credential
on the wire in plaintext. It would fail regardless — `:app` ships no
`network-security-config`, so the platform has blocked cleartext since API 28 —
but `UnknownServiceException: CLEARTEXT communication to ... not permitted`
names neither the setting nor the fix. A **local** proxy over `http://localhost`
would need a loopback exemption in the shipped manifest; that is a security
decision nobody has asked for, so it is refused rather than quietly allowed.

**The endpoint is stored in the clear, beside the key.** Two deliberate
asymmetries with §1's neighbour. It is *displayed back*, which the key never is
— a base URL the user cannot see is one they cannot notice is wrong, while being
exactly the setting that decides who receives their key. And it *survives*
`clear()`: "Remove" sits next to the key, and silently resetting a visible field
from that button is a worse surprise than leaving it. The cost is a test trap —
`clear()` no longer resets everything, so a test that sets an endpoint leaks it
into whatever runs next on the device. Every `setUp` here now resets it by hand.

**`Assistant.defaultClient` is `internal`, not `private`, so it can be tested.**
Every other test in the module injects a fake client factory, which would leave
the one line that actually reaches the SDK — the line that is the whole feature
— unexercised. Two tests call the real factory against MockWebServer and assert
the path the request lands on.

**What this does not buy.** Anything that does not implement `cache_control` or
thinking blocks silently loses §5 and §2: full price every turn, no error. A
structurally different provider is a port, not a setting — see `docs/PLAN.md`.

---

## 10. Judgements, so they are not re-litigated as bugs

- **`run_build` is `READ_ONLY`.** It writes only to the build cache, never the
  user's sources, so the plan's "confirm every mutating tool" does not reach it.
  A prompt there would turn fix → rebuild → check into three taps and train the
  user to approve without reading, which is how the one prompt that matters
  (`edit_file`) gets waved through.
- **Inline completion is a button, not a keystroke handler.** Every keystroke
  would be a request, spending the user's own money and battery. The tap is the
  budget.
- **Inline completion offers no tools.** A completion that stopped to read files
  would arrive after the user had typed the line; and since tools render first,
  a different tool list is a different cached prefix — paying for two entries to
  make completion slower is the wrong trade twice.
- **The build summary is not the build log.** Stage lines and dependency notes
  help nobody decide anything. The model gets the outcome plus the errors,
  capped at twenty, warnings dropped when there are errors.
- **The stored API key is never rendered back.** `ApiKeyStore` can decrypt it,
  but a screen that shows a secret has to be right about screenshots,
  accessibility services and the recents thumbnail forever after. "A key is
  saved" asks none of those questions.

---

## Still open — needs a real API key

Two questions are semantics rather than platform, and a local fake must not be
allowed to look like it settled them. They remain skipped tests in
`:spike:ai`'s `AnthropicOnDeviceTest`:

- **Does the live API accept this exact request shape?** The fake accepts
  anything.
- **Does prompt caching report a hit?** Everything in §5 is designed around it,
  and a miss is invisible without `cache_read_input_tokens` from a second turn.

```sh
./gradlew :spike:ai:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.anthropicApiKey=sk-ant-...
```
