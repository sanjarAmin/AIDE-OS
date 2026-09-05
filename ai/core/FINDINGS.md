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

**§§1–10 were written when this layer spoke to Anthropic and nothing else.**
Since 2026-09-02 it speaks to Gemini (the default), OpenAI and anything
OpenAI-compatible as well, and §§11–14 are what that cost. Read §11 first if you
are changing anything in `AiSession`: there are two tool loops now, and the
rules above hold in only one of them.

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

**This is Anthropic-only, but the hazard is not.** The other providers have no
thinking blocks to preserve, and the same mistake still breaks them in a
different shape. See §12.

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

**"One message" is a fact about this API, not about tool loops.** It holds for
Gemini and is false for OpenAI. Porting this section literally to another
provider asserts a bug — §12.

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

## 11. There are two tool loops, and the suite covered one of them

*Added 2026-09-02, with the multi-provider work.*

`AiSession` holds `sendAnthropic` and `sendGeneric`. They share `executeTool`
and nothing else — not the history, not the request assembly, not the rule about
batching results. `sendAnthropic` speaks the SDK's `MessageParam`;
`sendGeneric` speaks `AiClient`'s `AiMessage`/`AiPart`.

**The rules that live in the loop had to be re-established in the second one,
and the suite went on passing while they were not.** All 87 instrumented tests
in this module drove the Anthropic path, because that is the path they were
written for. A second loop with no §2 and no §3 in it is not a red suite; it is
a green one, and the assistant answers correctly on the provider nobody is
testing.

What carries over for free is whatever sits behind `executeTool`: §6's relative
paths are enforced by `ProjectFiles`, and the confirmation gate by
`ProjectToolset.execute`, so both loops inherit them. That is the whole list.
Anything above that line — history, request assembly, result batching — exists
twice and agrees only by hand.

The lesson generalises past this file: **a green suite is evidence about the
code paths it executes and nothing else.** Two implementations of one behaviour
need two sets of tests, and the second set does not write itself when the first
one passes.

`GenericSessionTest` is the second set — the nine cases from `AiSessionTest`
ported, plus two the generic path needs on its own, with `GeminiSessionTest` and
`OpenAiSessionTest` supplying the provider. It drives the **real**
`GeminiAiClient` and `OpenAiClient` against `ScriptedProviderApi` rather than a
fake `AiClient`, for the reason §7 gives about fakes: the wire format is where a
provider rejects a request, and a fake never emits one.

Two of them exist because the generic loop builds its own system instruction
rather than going through `PromptAssembler`, so nothing on the Anthropic side
guarantees either:

- **the tools are declared** — a request with no declarations gets a perfectly
  good answer; the model just never asks to read a file, and the assistant
  quietly degrades into a chatbot that cannot see the project;
- **the project context is in the instruction** — same shape, no error, it
  simply answers about a project it cannot see.

Both were verified by mutation rather than assumed: forcing `tools` to
`emptyList()` and dropping the assistant turn from the history produces eight
failures across the two providers, in exactly the tests that name those things.

---

## 12. The provider-neutral vocabulary leaks, and porting a rule can assert a bug

*Added 2026-09-02.*

`AiPart` — text, thought, function call, function response — is deliberately
thin, and it still does not mean the same thing on both sides of it.

**Tool results batch differently, and §3 is the trap.** Anthropic wants every
`tool_result` in one user message. Gemini agrees: one `user` turn of
`functionResponse` parts. OpenAI wants the opposite — one `role: "tool"` message
*per* call. A test ported literally from `AiSessionTest` asserts the Anthropic
shape and therefore asserts an OpenAI bug. `GenericSessionTest` keeps only the
provider-independent claim in the shared case (both calls ran, neither result
was dropped) and pushes the shape into each subclass.

**Call ids mean different things.** OpenAI issues a `tool_call_id` and rejects a
`tool` message carrying one it did not issue. Gemini has no call ids at all and
matches a result to its call by function name — so `GeminiAiClient` synthesises
a UUID on parse purely to satisfy the shared type, and that id never goes back
on the wire. The shared field invites one provider's convention into the other,
which is why the id round trip is pinned only where it is real
(`OpenAiSessionTest.a_result_carries_back_the_id_the_server_issued`) and why the
shared refusal case asserts on the tool *name*.

This bit during the port. The shared declined-edit test first asserted the id
appeared in the follow-up request; it passes on OpenAI and fails on Gemini, for
a correct implementation.

**§2's failure survives without §2's mechanism.** There are no thinking blocks
here, but the history still has to carry the turn that asked for a tool.
Dropping it leaves the result an orphan: OpenAI answers with a 400 naming an
orphaned `tool` message, Gemini just loses the thread. Same defect, different
symptom, no local sign of it either way.

---

## 13. `AiPart.Thought` does not round-trip on the Gemini path

*Added 2026-09-02. Known gap, not a fixed bug.*

`GeminiAiClient.parseResponse` only ever emits `AiPart.Text` and
`AiPart.FunctionCall` — it never constructs a `Thought`. The encoder, meanwhile,
writes a `Thought` out as an ordinary `text` part and drops its `signature`.

So thoughts cannot survive a turn on this path today. It is inert because
nothing produces one; it stops being inert the moment thought signatures are
parsed, and then it is §2 again with Gemini's spelling. Anything added here
needs the encoder and the parser changed together.

Adjacent and same species: the thinking budget is gated on
`model.contains("3.7") || model.contains("flash")`. That is a version substring,
and it rots the way §14 describes. It is currently harmless — every Flash model
in the list matches on `"flash"` — but the Pro models get no budget, which is a
product decision nobody has actually made.

---

## 14. Model IDs rot, and nothing catches it until a 404

*Added 2026-09-02.*

The provider menu shipped IDs that no longer existed: OpenAI's entire list was
the retired GPT-4o and o-series generation, and Anthropic's was three Claude 3.x
IDs beside one current model.

**A dead ID fails at none of the places that would catch it.** Not the build,
not startup, not when the user picks it from the menu. It fails on the first
request, as a 404 from the provider, which reaches the user as "the assistant is
broken" and reaches the log as someone else's error message.

They rotted because they were written in **five** places: `AiProviderType`, plus
a literal default in each of the three clients and in `ChatController`. The enum
offered models the client would never request and nothing said so. The clients
now derive their defaults from the enum, which makes it the only place a model
ID appears, and `AiProviderTest` pins that they agree.

**Check the provider's own documentation, not your memory of it.** This was
established the embarrassing way: `gemini-3.7-flash` and `gemini-3.1-pro-preview`
were called invented in this repo's own session notes, and both are real. The
stale lists were the two nobody doubted. A model ID is a fact with an expiry
date, and it is cheap to look up and expensive to guess.

---

## 15. A model in `models.list` is not a model you can call

`AiProviderType.GEMINI.availableModels` offered `gemini-2.5-pro`. The models
endpoint lists it, with `generateContent` among its
`supportedGenerationMethods` -- and calling it returns:

```
404  This model models/gemini-2.5-pro is no longer available to new users.
     Please update your code to use models/gemini-3.1-pro-preview
```

So the catalogue and the capability disagree, and the catalogue is the
optimistic one. **Checking a model id against `models.list` proves nothing**;
that check was run here first, reported all five ids present, and was wrong
about one of them. Only a request settles it, which is what
`GeminiOnDeviceTest.every_offered_model_answers` does -- one tiny call per model
in the picker, because the cost of a dead entry is a user selecting it and
getting an error that names the model as missing rather than retired.

This is §14's failure -- a model id that rots with nothing to catch it -- with
one addition: the id had not been renamed or removed, so even an id-existence
check against the catalogue would have passed.

`gemini-2.5-pro` is dropped from the picker. `gemini-3.1-pro-preview`, which the
error message recommends, was already there.

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

**The multi-provider work made this list longer, not shorter.** The first
question above now exists once per provider, and it is least answered for the
one that matters most:

- **Gemini: the test exists, the account does not pay for it.**
  `GeminiOnDeviceTest` (in `:ai:core`, not `:spike:ai` -- that spike is about
  whether a vendor SDK survives ART and does not depend on our code) drives
  `GeminiAiClient` against the live API and skips without a `geminiApiKey`
  argument. It has already earned its place by catching §15. The four
  assertions themselves are **still unproven**: with a valid key but no billing
  credit, `generateContent` returns
  `429 Your prepayment credits are depleted`, so whether Google accepts the
  request shape our client builds is *still* an open question. A key is not
  enough; the project behind it needs credit.
- **No request has ever reached OpenAI.** Every test of that client runs against
  `ScriptedProviderApi`, which proves the JSON matches *our reading of the spec*
  and nothing about whether the provider accepts it. One test modelled on
  `GeminiOnDeviceTest`, skipping without a key, is the cheapest way to close it.
- **Google Sign-In cannot complete.** `GoogleAuthManager.DEFAULT_CLIENT_ID` is a
  placeholder and not the shape of a real Google client ID. The PKCE mechanics
  are implemented and unit-tested, but the flow is dead until a real OAuth
  client is registered for this package. Gemini by pasted API key is unaffected,
  which is why this is easy to miss.
- **Prompt caching has no analogue on the generic path**, so §5 simply does not
  apply there. Whether these providers offer anything equivalent, and what it
  would cost to use it, is unexamined. Today the generic loop pays full price
  every turn.
