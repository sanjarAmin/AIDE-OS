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

> **Amended by §8: the ratio is not the signal this paragraph takes it for.**
> `llama-bench` on a real phone reads a 0.5B prompt at 103 tok/s with this same
> Termux engine, so nothing is structurally broken. A ratio near 1 appears when
> one side is memory-bandwidth-bound — a 7B streams 4.4 GB per token — and it is
> not evidence about instruction sets. §8 also measures what the missing
> instructions are actually worth: about a quarter, not an order of magnitude.

## 4. Tool use separates the model sizes, and the 1.5B is recoverable (but see §9)

The assistant reads and edits files through tools, so each model was offered a
`read_file` tool and asked what a file contains, five times:

| Model | Structured `tool_calls` | Right tool and path, written as JSON in the reply |
|---|---|---|
| 0.5B | 0/5 | **0/5** — it described the file's contents without reading it |
| 1.5B | 0/5 | **5/5** — `read_file` with `src/main/java/com/example/MainActivity.java` every time |

The 0.5B is unusable for the assistant, and worse than unusable: it answers
questions about files it never opened, plausibly.

> **§9 qualifies this section's conclusion.** The parsing problem below is real
> and is now solved, but "recoverable" was about the *transport*. Driven end to
> end, the 1.5B chose the right tool and then called it twelve times without
> ever answering. Choosing correctly is not terminating.

The 1.5B chooses correctly and then prints the call as a fenced `json` block
instead of in the markup the chat template expects, so the server's parser
never sees it. Two ways to use it, neither tried yet:

- **A lenient client-side parser**: treat a reply that is exactly one JSON
  object with `name` and `arguments` as a call. 5/5 here would parse.
- **A proper template**: `--chat-template-file` with Qwen2.5's tool-calling
  template, so the server parses it. Which template the server detected was not
  captured at the default log level — the first thing to check.

## 5. Cleartext to loopback: blocked by default, now exempted

**Measured before it was fixed** (NX809J, targetSdk 37, in the app's own
process):

```
java.io.IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted
```

`:app` shipped no `network-security-config`, so the platform default applied,
and **the default blocks loopback too**. This section previously asserted that
without a run behind it.

**The phone's browser opening `http://127.0.0.1:8080` is not a
counter-example**, and it is the first thing anyone reaches for -- the Node HTTP
template really does serve a page the browser really does load. Cleartext policy
is **per app**: the browser ships its own config and says nothing about ours.
Worth remembering, because it will confuse the next person exactly as it did the
last.

**Fixed, scoped to loopback.** `app/src/main/res/xml/network_security_config.xml`
permits cleartext for `127.0.0.1`, `localhost` and `::1` and nothing else.
Deliberately *not* `android:usesCleartextTraffic="true"`, which is one attribute
instead of a file and would permit plaintext everywhere -- including to the
remote providers, whose API key travels in a request header. On loopback there
is no wire to read it from; on the internet there is.

`LoopbackCleartextTest` pins both halves on a device: cleartext to `127.0.0.1`
now succeeds, and to a public host is still refused. The second assertion is the
one guarding the keys, and it is why the test checks the policy for
`example.com` rather than only the happy path.

**There were two walls, not one.** `Endpoint.parseEndpoint` rejected every
`http://` URL before a request was attempted, on reasoning that is right for a
remote endpoint and wrong for loopback. It now accepts http for the loopback
literals only -- matched by name, not by resolving DNS inside a parser -- and
**preserves the scheme it was given**, which it previously did not: it rebuilt
every accepted URL as `https://`, harmless while http could not get that far and
a silent rewrite once it could.

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

## 8. The phone numbers, a rebuilt engine, and a wrong turn worth recording

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

### The ratio is not the anomaly it looked like, and chasing it was a mistake

The first reading of the table above was that reading a prompt is batched and
should be *many* times faster than writing, so 1.34 meant the batched path was
broken. That inference drove everything that followed and **it was wrong**.
Recorded because the reasoning was seductive and the correction cost a day:

| | reading | writing | ratio |
|---|---|---|---|
| 0.5B, emulator (§3) | 20 | 13.8 | 1.45 |
| 1.5B, emulator (§3) | 9.7 | 6.8 | 1.43 |
| 7B, NX809J | 7.1 | 5.3 | 1.34 |

**The 7B's numbers are what a 7B costs on a phone.** `llama-bench` on the same
handset with Termux's own engine and the 0.5B gives **103 tok/s** prompt
processing. A 7B is about twelve times the parameters, and 103/12 ≈ 8.6 against
the 7.1 measured in the app — the rest being the memory pressure below. Nothing
exotic is happening.

And writing at 5.3 tok/s on a 4.4 GB model means streaming those weights once
per token: ~23 GB/s, which is roughly what a phone's memory bus delivers. **That
is a bandwidth wall, and no kernel work moves it.** A ratio near 1 is what you
get when one side is bandwidth-bound and the model is far too big for the
device; it is not evidence about instruction sets.

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

**This is a true fact about the package and it is not why the 7B was slow.**
It was written up here as the explanation before anything was measured with it,
which was the error: the disassembly confirms the *premise* of the argument
above — no fast paths — and says nothing about the *consequence*. What they are
worth is the next section, and it is far less than was claimed.

**The benchmark now reports this, so no future run needs a disassembly.** §7's
screen prints a `CPU backend` line from the server's own `system_info`, naming
the paths that are on *and* the accelerations that are off —
`NEON ARM_FMA FP16_VA — missing: MATMUL_INT8 DOTPROD SVE` is what Termux's
build should produce. Listing only what is enabled would read as everything
being fine, which is how this went unnoticed for a run.

### Rebuilt with the NDK: +27% reading, +36% writing, and one flag that costs 12x

Built from upstream (`aa39d7a`, ggml 0.24.0) with the NDK, and measured against
Termux's engine on the same phone, same model, both warm:

```
cmake -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF
```

| engine (0.5B Q4_K_M, NX809J, 8 threads, warm) | pp512 | tg64 |
|---|---|---|
| Termux `llama-cpp` 0.4.0 | 103.1 | 60.3 |
| this build, **OpenMP on** | 134.9 ± 24 | **6.5 ± 1.0** |
| this build, **OpenMP off** | **130.7 ± 0.1** | **82.1 ± 2.3** |

**`GGML_OPENMP=OFF` is not optional; it is worth 12x on generation.** With
OpenMP linked, writing collapsed to 6.5 tok/s — *eleven times slower than the
engine it was meant to replace*. Generation is thousands of small ops and the
per-op thread handoff dominates; prefill, being a few large batched ops, barely
noticed and looked fine. **Termux ships no `libomp.so` at all**, which is the
clue that was walked past: their binary would not link without one being added,
and that was the signal to leave OpenMP out, not to supply the library.

So the rebuild is worth doing — **+27% reading and +36% writing** — and it is
worth roughly a quarter, not the order of magnitude this document previously
implied.

### `GGML_CPU_ALL_VARIANTS` solves the SIGILL problem upstream

Baking `+i8mm` into one binary would crash every arm64 device older than
ARMv8.6. Upstream already handles this for Android specifically:

```
android_armv8.0_1                                     (no dotprod, no i8mm)
android_armv8.2_1    DOTPROD
android_armv8.2_2    DOTPROD FP16_VECTOR_ARITHMETIC
android_armv8.6_1    DOTPROD FP16 MATMUL_INT8
android_armv9.0_1    DOTPROD MATMUL_INT8 FP16 SVE2
android_armv9.2_1/2  … + SVE, SME
```

With `GGML_BACKEND_DL=ON` all seven are built; at startup ggml `dlopen`s each,
calls `ggml_backend_score()` and keeps the best. Verified: the NX809J selected
`android_armv9.2_2` on its own, and the disassembly confirms the split — 0 i8mm
instructions in `armv8.0_1`, 244 in `armv8.6_1`, 376 in `armv9.0_1` and above.

**This changes how the archive is launched.** `GGML_BACKEND_PATH` names *one*
`.so` (§2); variant selection needs a *directory* scan, and the search path is
the current working directory when none is given. So the server must be started
with its working directory set to the `lib` directory, rather than pointed at a
single backend file. A build shipping variants and launched the old way loads
nothing and falls back.

### The kernels are worth a lot, and the honest number is not pinned down

Forcing the same no-OpenMP build to one variant at a time, `GGML_BACKEND_PATH`
naming the `.so` directly, phone cooled to 43 °C first, 6 threads, 5 reps:

| variant | pp512 | tg32 |
|---|---|---|
| `android_armv8.0_1` (no dotprod, no i8mm) | 86.03 ± 0.09 | 63.77 ± 1.45 |
| `android_armv8.6_1` (dotprod + i8mm) | 212.88 ± **64.01** | 97.80 ± **13.98** |
| `android_armv9.2_2` (… + SVE, SVE2, SME) — *the one ggml picks* | 111.33 ± **33.28** | 64.44 ± 1.33 |

The direction is unambiguous — even the bottom of the i8mm variant's range is
well above the top of the baseline's — but **the magnitude is not measurable on
this device without more care than has been taken**. Two reasons, both worth
knowing before anyone repeats this:

- **The fast variant is unstable where the slow one is not.** The baseline came
  back at ±0.09 and the i8mm build at ±64 on the same phone, same cooldown, same
  five repetitions. That is not measurement error; it is the faster kernel
  reaching the thermal limit part-way through the run. A throttling curve is not
  a throughput number.
- **Thread count moves it more than the kernels appear to.** The auto-selected
  variant measured 130.7 at 8 threads and `armv8.6_1` measured 212.9 at 6. On a
  big.LITTLE phone, spilling onto the little cores costs more than it adds, so
  `-t 8` on an 8-core handset is not the right default and every number in this
  document taken at 8 threads understates the engine.

**And the variant ggml selects may not be the fastest one.** `armv9.2_2` scored
highest and was chosen automatically, yet measured *below* `armv8.6_1` on this
SoC — 111 against 213. `ggml_backend_score()` counts features, it does not
measure them, so a chip whose SVE or SME path is slower than its plain
NEON+i8mm path gets the wrong answer by construction. The error bars are too
wide to call this settled, but it is the first thing to check before shipping a
multi-variant archive: **benchmark the variants and consider pinning one**
rather than trusting the score. If it holds, the fix is to ship only up to
`armv8.6_1`, or to select by measurement at install time.

So: the rebuild's +27%/+36% headline (measured at 8 threads, both engines, warm)
is a *floor*, and the kernels are clearly most of it. **Pinning the real figure
needs a cooled phone, a thread sweep, and repetitions long enough for throttling
to reach steady state** — not a single run. Do not quote a number from this
section; quote the headline comparison and say it is a floor.

### The memory question, revisited

The 7B's pinned GGUF is 4,683,073,536 bytes ≈ 4467 MB and the server's RSS came
back 4453 MB against 4.4 GB free — the whole model resident with nothing spare,
mmap'd and therefore reclaimable (§7's note), so pages may be evicted and
re-faulted from flash.

Earlier revisions of this section demoted this to a secondary explanation
because the missing kernels supposedly accounted for the ratio. They did not,
so **this is now the stronger half of the 7B's story**, alongside a 7B simply
being twelve times a 0.5B. It is still not separated from plain model size, and
the run that would do it is the 1.5B on the same phone.

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

## 9. The 1.5B could not stop, and now does. Measured.

Driven on the NX809J, 2026-09-17: `llama-server` started from Settings, the
address published, a message sent from the chat panel, tool calls parsed and
executed against the project. **Every piece this app owns works.** What did not
work was the model.

Asked a bare `hello`, Qwen2.5-Coder 1.5B called `list_files` **twelve times in
succession** and never produced an answer. `AiSession`'s loop guard ended it:
*"I stopped after 12 rounds of tool calls without finishing."* Eighteen and a
half minutes of CPU time for no reply.

**This revised §4.** That section measured the 1.5B choosing `read_file`
correctly 5/5 and called it "recoverable", meaning the written-JSON calls could
be parsed. They can, and they were -- the tool cards in the panel are real
executions. But *choosing* the right tool is not the same as *deciding to stop*,
and a model that cannot terminate is not an agent however well it picks.

### The fix is two parts, and one part alone made it worse

**The prompt.** `sendGeneric` now says, in this order: call a tool when the user
asks you to look at, search or change a file; you *can* read any file, so never
claim otherwise; and answer directly, with no tool calls, for a greeting, a
general question, or anything already in the prompt. `LOCAL` gets a blunter
block after it with worked examples, because the 1.5B follows an example better
than a rule.

**Order matters, and getting it wrong is worse than the loop.** The first
version listed only the prohibitions. The 1.5B then *refused*: "the code is not
provided ... so I cannot read the file", while holding a `read_file` tool. A
loop wastes a user's time; a refusal is wrong, fast, and sounds certain. The
positive rule goes first.

**The guard.** A structural backstop that does not depend on the model reading
anything: every executed call is recorded by a signature over its name and its
arguments sorted by key, a repeat is answered with an error telling the model it
already ran that call and should answer now, and two consecutive rounds that
run nothing new end the session. Sorting the arguments is what makes
`{path, mode}` and `{mode, path}` the same call, which is how a model actually
re-issues one.

### The numbers

`LocalModelTerminationTest`, three cases against the live server through
`AiSession`, Qwen2.5-Coder 1.5B q4_k_m, server started by the app, phone idle
and not thermally limited, an 859-token prompt (a two-file Java project plus the
tool schemas):

| question | latency | tool calls | terminated |
| --- | --- | --- | --- |
| `hello` | 0.9 s | none | yes |
| `what does this project do?` | 2.2 s | none | yes |
| `read MainActivity.java and tell me what it displays` | 10.4 s | one, `read_file` | yes |

Tool selection was right in all three, in both directions: nothing for the two
that needed nothing, exactly one `read_file` for the one that needed it, and
prose after it in every case. Before the fix the first row was twelve rounds and
no answer.

**Three rows is a small sample, and it is the sample that matters.** §4 ran five
trials of the same *kind* of question and missed the defect entirely, because
selection and termination are different properties. One case per direction,
asserted strictly, found both of my bad intermediate states -- the loop and the
refusal.

### Read the latency with its conditions

The same three questions driven through the chat panel, on the real project and
as the first requests after the server started, took **56 s, 25 s and 84 s** --
25x the table above, with identical tool decisions. Three candidates: a cold
model, a much longer project listing, and the app's own work competing. **Not
isolated**, and the attempt to isolate it failed in a way worth recording (§10).
So: the table is warm-server, small-context, and the first question a user asks
after pressing Start costs tens of seconds. Both are true; neither is the
headline alone.

**The guard is still doing real work.** Without it a runaway session runs until
the read timeout, which for this provider is ten minutes because §8's model
sizes need it. A guard measured in *rounds* rather than seconds is the right
shape.

Untested: the 3B, which is not on the phone or this machine and is 2.1 GB of
someone's connection.

## 10. Two things the device said that no test had asked

Both found while collecting §9's numbers, which is the argument for collecting
them on hardware.

### A dead server kept its address

After the termination run the app was still up, `llama-server` was gone, and
`local.baseUrl` still named port 46819 -- so `ApiKeyStore.isReady(LOCAL)` was
true and the next message would have gone to a closed socket. There was no
tombstone newer than two days, which rules out a native abort and says nothing
about a SIGKILL; `logcat` returns nothing for app processes on this phone, so
lmkd's own line is not available either.

`adoptOrForgetExistingServer` did not cover it: that probes at launch, and the
app had not relaunched. **Nothing in the class ever learned the child had
died.** The fix hangs the death watch on the output drain, which is the one
thread already waiting on the process -- `forEachLine` returns when the child
closes its stream -- and withdraws the address there, guarded on process
identity so a stopped server's drain cannot clear a newer server's port.
`LocalServerDeathWatchTest` asserts both, with `sleep` standing in for the
server so it needs no model and is nobody's skip. It fails against the previous
build, which is the only reason to believe it.

**A child dying mid-session is the ordinary case here.** See below for why.

### Memory pressure, not CPU, is what breaks a local model on this phone

To separate "cold model" from "long prompt" in §9's latency gap, the server was
started again outside the app, through `run-as`, and the same three questions
re-run. The result was unusable and instructive: **prompt eval 11.92 tok/s and
generation 0.15 tok/s** -- 6.8 seconds per token, against roughly 18 tok/s
implied by the table above on the same phone an hour earlier.

The cause was not scheduling, which is what this looks like: `cpuset` was `/`,
`Cpus_allowed_list` was `0-7`, nice was 0. It was memory. The server's `VmRSS`
was 1.75 GB with **234 MB of it swapped out**, `MemFree` was 157 MB and about
3.9 GB of zram was in use. Killing it returned `MemFree` to 1.2 GB and
`MemAvailable` to 5.1 GB.

Three things follow:

- **A 1.5B on an 11 GB phone is not comfortably resident**, and §8's conclusion
  that 11 GB is the floor for the 7B understates the problem: what decides
  usability is what else the phone is holding. A 1.5B under swap is slower than
  the 7B was without it.
- **This is the likely reason the child died**, and it makes the death watch a
  routine requirement rather than a defence against force-stop.
- **`run-as` is not the app, in a third way.** `tools/clang/FINDINGS.md` §7 says
  it differs on exec and SELinux. It also differs in what else is running when
  you use it, so a performance number taken through it is not comparable to one
  the app produced. The latency gap in §9 stays open; measuring it needs the
  app's own launch path and a phone with memory to spare.

## What this makes a local model

**Viable to build, with the 1.5B as the smallest model worth offering**, and
worth building only once a phone's numbers are in:

1. ~~**Measure on a phone**~~ — done for the 7B (§8), and it rules the 7B out.
2. **Rebuild the engine, with `GGML_OPENMP=OFF` and `GGML_CPU_ALL_VARIANTS=ON`.**
   Measured at +27% reading and +36% writing over Termux's build (§8) — a real
   gain, and a smaller one than this list once claimed. The OpenMP flag is the
   part that must not be got wrong: with it on, writing is *eleven times slower
   than the engine being replaced*. Packaging it also means launching the
   server with its working directory in `lib/`, because variant selection scans
   a directory where `GGML_BACKEND_PATH` names one file.
2. ~~**Make tool calls structured**~~ — the lenient parser is in (§8). A
   natively parsed template is still worth having, for the call id.
3. ~~**Allow loopback cleartext**~~ — done (§5): a config scoped to the
   loopback literals, a matching `parseEndpoint` exception, and a device test
   asserting both that loopback works and that a public host still does not.
4. **The feature, partly built.** `AiProviderType.LOCAL` exists and
   `Assistant` builds a session and a completer for it; `ApiKeyStore` treats a
   published address as its readiness signal, since it has no key;
   `LocalModelServer` starts `llama-server` on a free loopback port, waits for
   `/health`, publishes the address and withdraws it on stop. Context is capped
   at 4096 for the reason this item always gave — the project listing goes with
   every question and a phone pays for it twice, in KV cache and in
   prompt-reading time.

   ~~**What is not done: nothing calls `LocalModelServer`**~~ — the control is
   in Settings now (status, a size picker, start/stop), and the path works end
   to end on hardware (§9). Two defects that only driving found: the HTTP read
   timeout was OkHttp's ten-second default, which a model generating on a phone
   cannot meet; and a force-stop left the published address behind, so the
   provider reported itself ready with nothing listening. Both fixed.

   ~~**What remains is the model, not the plumbing**~~ — the 1.5B terminates
   now, on a rebalanced prompt plus a guard that refuses to re-run a call it
   has already run, measured at 0.9–10.4 s and 0–1 tool calls across three
   cases on the phone (§9). **The feature is usable.** What it is not yet is
   fast on the first question after Start, and that number has conditions worth
   reading before quoting it.

Still open, and reordered by what the device actually did:

- **Memory, which turns out to be the binding constraint** (§10). A 1.5B under
  swap generates at 0.15 tok/s on this phone. The open question is not whether
  a 6–8 GB phone can *hold* the 1.5B but what it is holding already, and the
  honest answer may be that this feature needs a free-memory check before it
  offers to start anything.
- **What the first question after Start really costs** (§9): 25x the warm
  number, cause not isolated, and not measurable through `run-as`.
- **Whether Android freezes or kills the child when the IDE is
  backgrounded** — the debugger's §9 problem. Partly answered from the wrong
  direction: a child *did* die unasked, and the app now notices. Whether the
  freezer or lmkd did it is still unmeasured.
- **The 3B**, unmeasured and not on any disk here.
- **The Vulkan backend on a phone's GPU**, untried.
