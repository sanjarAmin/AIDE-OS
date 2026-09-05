# Spike R-AI — the Anthropic SDK against the live API, on a device

What `AnthropicPlatformTest` could not answer. That suite proves the SDK's
object graph builds and speaks to a local server on ART; these four tests point
it at the real API, because "pure JVM, therefore fine on ART" has been wrong in
this project three times and "the local server accepted it" is a weaker claim
than it sounds.

Run with a key, which is never committed and never written into the repo:

```sh
# ~/.gradle/gradle.properties, outside the repo, mode 600
android.testInstrumentationRunnerArguments.anthropicApiKey=sk-ant-...
```

Every test skips without one. A checkout with no key is the normal state, and a
red suite would say the wrong thing about the code.

## 1. The SDK works against the live API from an emulator

All four questions answer yes: the client constructs on ART, a request
completes, streaming arrives in many events rather than one buffered blob, and
prompt caching reads back. Nothing needed a shim, a stub jar or a workaround --
unlike kotlinc (seven startup fixes), ECJ (a shim plus a stubs jar) and
maven-resolver (four workarounds). The parenthesis in `docs/PLAN.md` -- "(OkHttp
works fine on Android)" -- holds for the SDK wrapped around it too.

## 2. Prompt caching reads back on Sonnet 5 and not on Opus 5

**This is the finding with a cost attached**, because `docs/PLAN.md` calls
caching "the cost lever" and lays the whole prompt out around it.

Measured on one account, same code, same 6 KB system prompt, same key, only the
model changed:

| model | turn 1 | turn 2 | turn 3 (after a 5 s pause) |
|---|---|---|---|
| `claude-sonnet-5` | written 6146, read 0 | **written 0, read 6146** | written 0, read 6146 |
| `claude-opus-5` | written 6145, read 0 | written 6145, **read 0** | written 6145, read 0 |

Opus writes a fresh entry every turn and never reads one. The third turn, after
a pause, rules out a propagation race -- the first guess, and the wrong one.

**The app's Anthropic default is `claude-opus-5`** (`AiProvider.defaultModel`),
so on the default model every turn re-pays for the whole system prompt. Whether
that is a property of the model, the account or the tier is **not established
here**; what is established is the difference, and that it is not in our code.
Worth re-measuring before relying on caching for cost, and worth measuring on
Gemini and OpenAI too, which have their own caching and their own defaults.

The test therefore asserts the *mechanism* on a model where it demonstrably
works, and records the rest here. A test that pinned Opus's behaviour would fail
the day it changes, which is not a fact about this project.

## 3. The cache outlives the test run, and an assertion assumed it would not

The first version demanded `cache_creation_input_tokens > 0` on turn one. Entries
live about five minutes, so a second run inside that window finds the prefix
already there, *reads* it, and the suite fails for having been run twice --
"nothing was cached on the first turn", which reads like a broken feature and is
a broken assumption.

It asserts written **or** read on the first turn now. The claim worth making is
that the prefix is cached and the next turn reuses it, not that this particular
run is the one that created it.

## 4. What a live suite still does not cover

- **Only Anthropic.** Gemini is the app's *default* provider and OpenAI is
  supported; both are unverified against their live APIs, and each has its own
  request shape and its own caching.
- **Google Sign-In is untouched.** `GoogleAuthManager`'s OAuth client id is a
  placeholder, and an API key does not help -- it needs a client registered for
  the package.
- **One account, one day.** Everything above is a measurement, not a guarantee:
  rate limits, tiers and model behaviour differ per account.
