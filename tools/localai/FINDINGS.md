# A coding model on the phone

Spike R16, 2026-09-13. Asked whether AIDE-OS can offer an **optional local
model**: nothing in the APK, downloaded only by someone who wants it, answering
the assistant without a network or a key.

The design under test is deliberately small. Termux publishes llama.cpp for
Android, so `llama-server` can arrive the way clang and Node did — an optional
download started through `/system/bin/linker64` from app storage — and it
serves the OpenAI chat API the assistant's Custom provider already speaks. If
that holds, a local model is a download and a process, and no new AI code.

Everything below was measured on the `aideos_test` emulator. **The speeds are
not a phone's** (see §3); the route, the memory, the tool-calling behaviour and
the platform policy are.

## 1. The engine is a small download, and it starts from app storage

Termux's index carries `llama-cpp` 0.4.0 for aarch64 and x86_64, about 7 MB
each, depending only on `libc++`, `libcurl` and `libandroid-spawn`. There are
also `llama-cpp-backend-vulkan` and `-opencl` packages, and `ollama`.

`fetch-llama.sh` builds the closure into `llama.tar` — **30 MB** with every
llama.cpp tool in it; the server and its libraries alone would be less.
`llama-server --version` answers `built with Clang 21.0.0 for Android x86_64`,
and the server is **healthy 1.3–1.8 s after start** with a 468 MB model.

## 2. ggml cannot find its own CPU backend under this launch

The first start loaded nothing:

```
llama_model_load_from_file_impl: no backends are loaded.
```

ggml's CPU backend is a plugin, `libggml-cpu.so`, which ggml finds by
searching three places: Termux's compiled-in prefix
(`/data/data/com.termux/files/usr/lib`, which this app does not have), the
directory of `/proc/self/exe`, and the working directory. Under the linker
launch `/proc/self/exe` **is** `/system/bin/linker64` — Node's `execPath`
problem again, one library down — so none of the three holds it.

`GGML_BACKEND_PATH=<prefix>/lib/libggml-cpu.so` names the file outright, and
the model loads. `LD_LIBRARY_PATH` is needed too, for the RUNPATH reason
`tools/node/FINDINGS.md` gives.

## 3. Emulator speed is not phone speed, and why

| Model | Reading a prompt | Writing |
|---|---|---|
| Qwen2.5-Coder 0.5B Q4_K_M | 20 tok/s | 13.8 tok/s |
| Qwen2.5-Coder 1.5B Q4_K_M | 9.7 tok/s | 6.8 tok/s |

A realistic assistant prompt — a 60-file listing plus a file, 2,190 tokens —
took **110 s** to read before the first word, on the 0.5B.

Reading a prompt normally runs several times faster than writing, because it
is batched. It did not here because **the emulator's virtual CPU hides the
instructions ggml's fast x86 paths need**: the host has AVX2, the guest
reports `ssse3 sse4_2 avx f16c` and no AVX2 or FMA. A phone's cores take a
different path (NEON, and `dotprod`/`i8mm` on recent ones). **These numbers
establish that the route works and nothing else.** Measuring on a phone is the
next step, and prompt-reading speed is the number to watch: the assistant
sends the project listing with every question.

## 4. Tool use separates the model sizes, and the 1.5B is recoverable

The assistant reads and edits files through tools, so each model was offered a
`read_file` tool and asked what a file contains, five times:

| Model | Structured `tool_calls` | Right tool and path, written as JSON in the reply |
|---|---|---|
| 0.5B | 0/5 | **0/5** — it described the file's contents without reading it |
| 1.5B | 0/5 | **5/5** — `read_file` with `src/main/java/com/example/MainActivity.java` every time |

The 0.5B is unusable for the assistant, and worse than unusable: it answers
questions about files it never opened, plausibly.

The 1.5B chooses correctly and then prints the call as a fenced `json` block
instead of in the markup the chat template expects, so the server's parser
never sees it. Two ways to use it, neither tried yet:

- **A lenient client-side parser**: treat a reply that is exactly one JSON
  object with `name` and `arguments` as a call. 5/5 here would parse.
- **A proper template**: `--chat-template-file` with Qwen2.5's tool-calling
  template, so the server parses it. Which template the server detected was not
  captured at the default log level — the first thing to check.

## 5. The app may not talk to it yet: cleartext to loopback is blocked

For an app targeting SDK 37 with no network security config — which is what
AIDE-OS ships — `NetworkSecurityPolicy` reports:

```
anywhere=false  127.0.0.1=false  localhost=false
```

So OkHttp in the app would refuse `http://127.0.0.1:<port>` before connecting,
and `parseEndpoint` refuses `http` outright. A local model needs **both**: a
network security config permitting cleartext to `127.0.0.1` only, and an
exception in `parseEndpoint` for loopback. Neither leaks anything off the
device, but both are security settings and should be changed deliberately, not
as a side effect. The spike talks HTTP over a raw socket precisely so this
policy could not block the other questions.

## 6. Two harness traps

- **Files pushed while `adb` is root cannot be opened by the app.** After
  `adb root` — used to diagnose the frozen debuggee — pushes landed
  `root:root` on the FUSE-backed external storage, and `llama-server` failed
  with `Permission denied` on the model. `adb unroot` and a fresh push
  (`shell:ext_data_rw`) fixed it. A real download written by the app has no
  such problem.
- **An uncaught exception on any thread kills the test process.** The thread
  copying the server's output threw when `tearDown` destroyed the server, and
  took every later test in the class with it.

## 7. The benchmark screen: the phone half of this spike

Settings → AI Assistant → **Local model benchmark (preview)** downloads the
engine and a model through `:toolchain:manager` and runs this spike's questions
on the user's phone, with a Copy results button. It exists because §3's number
cannot come from an emulator.

- **The engine is a component** (`llama-cpp-0.4.0`, 12 MB per ABI, on this
  repo's releases), trimmed to `bin/llama-server` and the libraries it loads.
  Trimming was checked by running the trimmed archive before publishing.
- **Models come from Hugging Face directly**, pinned by revision and by the
  **SHA-256** the page publishes. `ToolchainComponent.archiveSha256` exists for
  them: pinning by SHA-1 would have meant downloading 8 GB to a laptop to
  compute a weaker digest. A model is a `ComponentArchive.SingleFile`,
  installed by rename, so it never needs room for two copies.
- **`PinnedReleaseTest` hashes every archive except the models**, which it
  checks by size alone. A size cut-off let the 491 MB model into the full hash
  and the test JVM ran out of heap.

Driven on the emulator end to end — engine from GitHub, the 0.5B from Hugging
Face, Run — it reproduced the spike: ready in 1.5 s, 12.6 tok/s writing,
19.5 tok/s reading (2,190 tokens in 112 s), 0/3 tool calls. Two things the
drive changed:

- **"Less free RAM" is the wrong memory number.** The model is memory-mapped
  and Android counts those pages as reclaimable, so a server with 576 MB
  resident showed as 129 MB less free. The report gives the server's resident
  memory, read after the long prompt.
- **Results appeared below the model list**, so a tap on Run changed nothing on
  screen until the user scrolled. They are at the top, and the list scrolls to
  them.

## What this makes a local model

**Viable to build, with the 1.5B as the smallest model worth offering**, and
worth building only once a phone's numbers are in:

1. **Measure on a phone**: startup, prompt-reading and writing speed for the
   1.5B and 3B, and memory. The emulator cannot answer this; the benchmark
   screen (§7) is how.
2. **Make tool calls structured**: the template first, the lenient parser as
   the fallback.
3. **Allow loopback cleartext** deliberately: a network security config for
   `127.0.0.1` and a matching `parseEndpoint` exception.
4. Then the feature: `:toolchain:manager` components for the engine and each
   model (pinned, like everything else), a "Local model" provider that starts
   `llama-server` and points the OpenAI client at it, and a smaller context for
   it — the project listing sent with every question is the expensive part.

Still unasked: whether Android freezes or kills a `llama-server` child process
when the IDE is in the background (the debugger's §9 problem), whether the
Vulkan backend works on a phone's GPU, and memory pressure on a 6–8 GB phone.
