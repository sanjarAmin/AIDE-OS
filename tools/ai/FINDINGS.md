# Spike R5: the Anthropic SDK on ART — result

**Outcome: the platform half is resolved, and it is the good outcome for once.**
`com.anthropic:anthropic-java` 2.57.0 dexes, loads, serialises, parses and
streams on ART with **no workarounds in the SDK itself** — the first
"pure JVM, therefore fine on ART" claim in this project that turned out to be
true. Two things around it did need fixing, and neither is the SDK's fault.

`docs/PLAN.md` settles the platform in a parenthesis — "(OkHttp works fine on
Android)". That is the same shape of claim that cost seven startup fixes on
kotlinc, a `javax.lang.model` shim plus a stubs jar on ECJ, and four
workarounds on maven-resolver. It deserved checking. It survived.

Measured on the `aideos_test` AVD (API 34, x86_64). Reproduce with
`:spike:ai:connectedDebugAndroidTest`; the platform tests need no key.

## What works, unmodified

| Question | Answer |
|---|---|
| Does the client construct on ART? | Yes. `AnthropicClientImpl`, no reflection failure, no Jackson trouble. |
| Does a request round-trip? | Yes. Request serialised, response parsed, `usage` populated. |
| Do `thinking` and `effort` reach the wire? | Yes — verified against the actual request body, not the builder. |
| Does streaming arrive in pieces? | Yes. Seven chunks arrived as seven separate deltas. |

The request the SDK emits for the plan's configuration, read off the wire:

```json
{"max_tokens":64,"messages":[{"content":"ping","role":"user"}],
 "model":"claude-opus-5","output_config":{"effort":"low"},
 "thinking":{"type":"adaptive"}}
```

That is exactly the shape `docs/PLAN.md` specifies, which matters because
`effort` is the per-feature cost lever — `low` for inline completion, `high`
for chat — and a builder call that silently failed to reach the JSON would be
invisible until a bill arrived.

## 1. `com.sun.net.httpserver` is not on Android

The obvious way to write a local test server, and it does not exist on any API
level:

```
java.lang.NoClassDefFoundError: Failed resolution of:
  Lcom/sun/net/httpserver/HttpServer;
```

Worth recording precisely because this repository already contains a fixture
that uses it — `toolchain/manager/src/test/.../ArchiveServer.kt`. That one works
because it is a **JVM unit test**. Copying the pattern into an instrumented test
fails at run time. Use **MockWebServer**, which is built for this and runs on
the same OkHttp the SDK already pulls in.

## 2. Android blocks cleartext, so a local test server is unreachable by default

```
java.net.UnknownServiceException: CLEARTEXT communication to localhost
  not permitted by network security policy
```

Cleartext has been off by default since API 28, and MockWebServer serves plain
HTTP. Presents as `AnthropicIoException: Request failed` from inside the SDK,
which reads like an SDK fault and is not one — the cause is three frames down.

Fixed with a `network-security-config` scoped to loopback rather than a blanket
`usesCleartextTraffic`. The file lives in `androidTest` and never ships, but a
fixture that quietly permits plaintext everywhere is the kind of thing that gets
copied into a real manifest later.

## Core library desugaring is on, and stays on

`:spike:ai` enables it because a JVM SDK uses `java.time` and streams freely and
a library module does not inherit `:app`'s setting. Nothing here proves it was
*required* — desugaring was enabled from the first run, so the counterfactual
was never tested. It is cheap and the failure it prevents is a run-time
`NoClassDefFoundError`, so `:ai:core` should keep it rather than find out.

## Still open — needs an API key

Two questions are **semantics, not platform**, and a local fake must not be
allowed to look like it settled them. They stay in `AnthropicOnDeviceTest`,
skipping until someone passes a key:

- **Does the real API accept this exact request shape?** The fake accepts
  anything. Only the live endpoint validates.
- **Does prompt caching report a hit?** `docs/PLAN.md` calls caching "the cost
  lever" and designs the whole prompt layout around it. It is also the feature
  most likely to silently not work: a cache miss returns a perfectly good answer
  and simply costs more, so nothing surfaces unless `cache_read_input_tokens` is
  read back on a second turn.

Run them with:

```sh
./gradlew :spike:ai:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.anthropicApiKey=sk-ant-...
```

The key is an instrumentation argument: never written to disk, never logged,
never committed.

## Verified on device

`:spike:ai:connectedDebugAndroidTest` on `aideos_test` (API 34, x86_64),
2026-08-25. Four platform tests pass; four live tests skip for want of a key.
