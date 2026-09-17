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
each, depending only on `libc++`, `libcurl` and `libandroid-spawn`. **0.4.0 is
Termux's packaging version, not upstream llama.cpp's** — the number says
nothing about how recent the engine is, and §8 found that what matters about
this package is not its age but its architecture flags. There are
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

> **Amended by §8, and the cause is one level up from this paragraph.** A phone
> with `i8mm`, `sve2` and `bf16` produced the *same* reading-to-writing ratio.
> Disassembling the engine shows why: Termux's build has **no** i8mm, dotprod
> or bf16 instructions in any of its libraries, on either ABI. The emulator's
> missing AVX2 is a symptom of the same thing — Termux packages for a portable
> baseline everywhere — so "the guest CPU hides what ggml needs" was true of
> the emulator and beside the point. §8.

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

## 8. The phone numbers, and the one prediction §3 got wrong

Measured 2026-09-16 with the §7 benchmark screen, on a **nubia NX809J**,
Android 16 / API 36, QTI SM8850, 8 cores, 11.0 GB RAM (4.4 GB free), CPU
features `asimd asimddp sve sve2 i8mm bf16`. Qwen2.5-Coder **7B** Q4_K_M:

```
Startup   ready in 4.1 s          Threads  8
Writing   5.3 tok/s (128 tokens)
Reading   7.1 tok/s — 2190 tokens took 306.8 s before the first word
Memory    server uses 4453 MB (resident)
Tool calls  0/3 structured, 3/3 written as JSON in the reply, 0/3 no call
```

### Prefill is still barely faster than generation, and §3's reason cannot explain it

| | reading | writing | ratio |
|---|---|---|---|
| 0.5B, emulator (§3) | 20 | 13.8 | 1.45 |
| 1.5B, emulator (§3) | 9.7 | 6.8 | 1.43 |
| **7B, NX809J** | **7.1** | **5.3** | **1.34** |

Reading a prompt is batched and should run *many* times faster than writing.
§3 blamed the emulator's virtual CPU for hiding the instructions ggml's fast
paths need, and said a phone's cores take a different path — `dotprod`/`i8mm`
— with the clear implication that the ratio would improve on hardware. **This
phone has `i8mm`, `sve2` and `bf16`, and the ratio did not move.** So the
missing AVX2 was never the whole account, and §3 should be read as describing a
symptom rather than the cause.

### The engine cannot issue the instructions the phone has. Confirmed statically.

**Termux's `llama-cpp` 0.4.0 is built for baseline NEON and nothing else.**
Disassembling every one of the 18 shared libraries in the aarch64 archive
(`llvm-objdump` from the NDK, no device needed):

| instruction class | count |
|---|---|
| `smmla` / `ummla` / `usmmla` (i8mm) | **0** |
| `sdot` / `udot` (dotprod) | **0** |
| `bfmmla` / `bfdot` (bf16) | **0** |
| SVE predicates (`ptrue`, `whilelt`) | **0** |
| `fmla` (plain NEON) | 1,476 |

The last row is the control: the disassembly decoded real ARM code, so the
zeroes are absence and not a failed dump. There are no runtime-dispatched
variants either — a multi-variant ggml build ships `libggml-cpu-*.so`
alternatives and this archive has exactly one `libggml-cpu.so`.

**Grepping for the feature names finds them, and that is a trap.**
`MATMUL_INT8`, `DOTPROD` and `ggml_cpu_has_matmul_int8` are all present as
strings — they are what `llama_print_system_info()` prints, and the function
exists whether or not the kernels were compiled. The strings say yes and the
instructions say no. **Only the disassembly settles it**, which is the same
shape as every other finding in this repo where something answers plausibly and
wrongly.

This is also the common factor §3 was reaching for. §3 blamed the *emulator's*
virtual CPU for hiding AVX2; the real cause is one level up — **Termux packages
for a portable baseline on every architecture**, so the x86_64 build misses
AVX2 for the same reason the aarch64 build misses i8mm. One explanation, both
ABIs, and it predicts the ratio staying ~1.4 on hardware, which is what
happened.

**So the fix is the engine, not the model.** `fetch-llama.sh` repackages
Termux's `.deb`; getting the kernels means compiling llama.cpp with the NDK
against `armv8.2-a+dotprod+i8mm` (or enabling ggml's multi-variant dispatch)
and pinning that instead — this repo already drives the NDK for
`tools/treesitter/build-grammars.sh`, so the machinery exists. **The speedup is
predicted, not measured**: what is established here is that the fast paths are
absent, not what they are worth once present.

**The benchmark now reports this, so no future run needs a disassembly.** §7's
screen prints a `CPU backend` line from the server's own `system_info`, naming
the paths that are on *and* the accelerations that are off —
`NEON ARM_FMA FP16_VA — missing: MATMUL_INT8 DOTPROD SVE` is what Termux's
build should produce. Listing only what is enabled would read as everything
being fine, which is how this went unnoticed for a run.

### The memory question is still open, and no longer needed to explain the ratio

The 7B's pinned GGUF is 4,683,073,536 bytes ≈ 4467 MB and the server's RSS came
back 4453 MB against 4.4 GB free — essentially the whole model resident with
nothing spare, mmap'd and therefore reclaimable (§7's note), so pages may be
evicted and re-faulted from flash. That would *also* flatten reading and
writing toward the same rate.

It is no longer a competing explanation — the missing kernels account for the
ratio on their own, and on the emulator too, where memory was never tight. It
remains a plausible *additional* cost on this phone, and the run that would
show it is the 1.5B (1.1 GB, fits comfortably) on the same device. Worth doing
after the engine is rebuilt, not before: with baseline NEON on both, the two
sizes are not expected to differ in ratio, so the experiment tells you little
until the kernels are there.

### The 7B is not the model to ship, whatever the cause

**306 s before the first word**, on the 2,190-token prompt §3 chose *because it
is realistic* — a 60-file listing plus a file, which is what the assistant
sends with every question. That is not a worst case. Even a tenfold prefill fix
leaves half a minute of silence per turn, and the model needs 4.4 GB of an
11 GB phone to do it.

### Tool calls: not something a bigger model grows out of

§4 measured the 1.5B at 0/5 structured, 5/5 written as JSON, and left two
untried fixes. This run narrows them:

- **`--jinja` alone is not enough.** The benchmark already launches with it
  (§7), and the 7B still produced 0/3 structured.
- **Fourteen times the parameters changes nothing.** 1.5B 0/5, 7B 0/3. The
  models are not failing to choose — 0/3 "no call", and 3/3 named the right
  tool with the right path. Only the parsing loses it.

So §4's "lenient client-side parser" is the option with evidence behind it, and
it is **implemented**: `OpenAiClient.recoverWrittenCall` turns a written call
into a real one when the object names a tool *that request offered* and carries
`arguments`. The name check is the guard that makes it safe — an IDE assistant
is asked to show JSON constantly, and recovering "a reply containing an object"
would execute tools the user only asked to look at. `OpenAiClientTest` pins
both directions, including a `package.json` answer that must **not** be
executed.

A chat template that the server parses natively would still be better: a
recovered call has no id, so it relies on the generic loop matching results by
name, which is the path Gemini needs anyway.

## What this makes a local model

**Viable to build, with the 1.5B as the smallest model worth offering**, and
worth building only once a phone's numbers are in:

1. ~~**Measure on a phone**~~ — done for the 7B (§8), and it rules the 7B out.
2. **Rebuild the engine with the kernels.** §8 establishes that Termux's build
   can issue no i8mm, dotprod or bf16 instruction at all, which is why reading
   a prompt is no faster than writing one. Compile llama.cpp with the NDK
   against `armv8.2-a+dotprod+i8mm` and pin that in place of the repackaged
   `.deb`. **This is the one that matters**; everything else here is tuning
   around a missing fast path. Then re-measure the 1.5B and 3B.
2. ~~**Make tool calls structured**~~ — the lenient parser is in (§8). A
   natively parsed template is still worth having, for the call id.
3. **Allow loopback cleartext** deliberately: a network security config for
   `127.0.0.1` and a matching `parseEndpoint` exception.
4. Then the feature: `:toolchain:manager` components for the engine and each
   model (pinned, like everything else), a "Local model" provider that starts
   `llama-server` and points the OpenAI client at it, and a smaller context for
   it — the project listing sent with every question is the expensive part.

Still unasked: whether Android freezes or kills a `llama-server` child process
when the IDE is in the background (the debugger's §9 problem), whether the
Vulkan backend works on a phone's GPU, and memory pressure on a 6–8 GB phone —
§8 suggests an 11 GB phone is already the floor for the 7B, so a 6 GB one is a
question about the 1.5B rather than about the range.
