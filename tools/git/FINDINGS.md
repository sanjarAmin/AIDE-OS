# Spike R6: JGit on ART — result

**Outcome: it works, unmodified, with no workarounds.** That is the first time
this project has been able to write that sentence.

`docs/PLAN.md` lists JGit as "Pure JVM", and the same phrase has cost three
milestones: kotlinc needed seven fixes before it would start
(`tools/kotlinc/FINDINGS.md`), ECJ needed a hand-written `javax.lang.model`
shim and a stubs jar (`tools/ecj/FINDINGS.md`), and maven-resolver needed four
workarounds and ruled out its own 2.x line (`tools/deps/FINDINGS.md`). The
prior was that JGit would need something too. It needs nothing.

JGit **7.7.1.202607240634-r**, measured on the `aideos_test` AVD (API 34,
x86_64). Reproduce with `:spike:git:connectedDebugAndroidTest` and read the
numbers out of logcat under the tag `GitSpike`.

## What works, and what it costs

| | |
|---|---|
| `FS.detect()` | `FS_POSIX`, **0 ms** |
| `init` + `add` + `commit` | works |
| History through a `RevWalk` | works, in order |
| Shallow clone over HTTPS, small repo | **1.4 s** (`octocat/Spoon-Knife`) |
| `status` on a clean working tree | **13 ms** |
| `status` after one edit | **11 ms** |
| `push` to a bare repository | `OK`, objects readable on the far side |

Five layers, five passes, first run. The only failure of the whole spike was a
missing `INTERNET` permission in the test manifest, which is not a finding
about JGit.

## 1. `FS.detect()` does not care that there is no `git` and no `/bin/sh`

This was the doubt that mattered most, because everything else is downstream of
it. JGit's filesystem abstraction probes its environment on first use: it looks
for a `git` executable on `PATH` to read the system config, and on POSIX it
shells out to learn the umask. Android has no `git` anywhere and its shell is
at `/system/bin/sh`, not `/bin/sh`.

It resolves to `FS_POSIX` in **0 ms** and reports:

```
supportsExecute  = true
supportsSymlinks = true
gitSystemConfig  = null
userHome         = null
```

The nulls are the correct answers, not degradations, and JGit treats them as
such — a missing system config is an ordinary state on a machine where git was
never installed. **`:vcs:git` gets no global or system configuration**, which
has two consequences worth designing around rather than discovering:

- **There is no `user.name` / `user.email` to fall back on.** JGit's default
  identity is assembled from system properties Android fills in differently, so
  every commit must set `PersonIdent` explicitly. The spike does; so must the
  module, and the app needs somewhere for the user to enter it before their
  first commit rather than after.
- **There is no credential helper and no `~/.gitconfig`.** Whatever stores a
  token has to be ours. `:ai:core`'s `ApiKeyStore` is the model — Android
  Keystore, AES/GCM — and the same reasoning applies more strongly, because a
  git token is usually broader in scope than an API key.

`supportsExecute` and `supportsSymlinks` are `true` on app-internal storage
(`cacheDir`). Neither is guaranteed elsewhere: a repository on external or
SAF-backed storage is a different filesystem with different answers, and
`:core:fs` already records that a project imported through SAF has to be
*copied* because aapt2 needs a real path. The same constraint applies here for
the same reason, so it costs nothing new.

## 2. Java 17 bytecode dexes without incident

The artifact targets Java 17. D8 desugars it, and nothing JGit reaches at
runtime turned out to be a JDK 9+ API Android lacks — `ProcessHandle` was the
one to watch, since it is what a modern JVM uses to reap the subprocesses of
finding 1, and it is never reached because the probe finds nothing to run.

`java.nio.file` is used throughout and behaves. The read side is the part that
would fail quietly rather than loudly — `FileSnapshot` compares file attributes
to decide whether its caches are stale, and an attribute that answers wrongly
shows up as a `status` that reports a clean tree as dirty, or history that is
short. Both are asserted in the spike rather than assumed, which is why
`status_sees_a_working_tree_edit` checks *which* file it names and
`history_reads_back_in_order` checks the order.

## 3. The transport is `HttpURLConnection`, which is why it works

maven-resolver 2.x was ruled out for this project because its transport is
`java.net.http.HttpClient`, which Android does not have at any API level, and
maven-resolver 1.9's Apache HttpClient could not work either because the
platform ships its own stripped `org.apache.http` on the **boot classpath**
(`tools/deps/FINDINGS.md` §2).

JGit's HTTP transport is built on `HttpURLConnection`. That is the one HTTP
stack Android has always had and always supplies itself, so there is nothing to
shade, replace or shim. **SSH is a separate artifact and was not spiked**: the
acceptance test says "clone from GitHub", and HTTPS with a token is the path a
phone can take without key management. If SSH is wanted later it is a new
question, not an answered one.

## 4. Push was tested over `file://`, deliberately

Pushing to GitHub needs a credential this suite must not carry. The half of
push that was in doubt on Android is not the credential — it is pack
generation, which walks objects, deflates them, and writes a pack the receiving
side indexes. That happens identically over either transport, so the spike
pushes to a local bare repository and asserts the *receiving* repository can
resolve the commit, rather than trusting the push result's `OK`.

**What this does not answer is whether GitHub accepts the result.** That needs
a token and belongs in the same parked category as M5's live-API assertions:
real, known, and not blocked on.

## 5. Cost is a design input, not a footnote

`status` at 11–13 ms is comfortable: it can run after every save, which is what
an IDE that decorates its file tree needs.

Clone is the opposite. Cloning `git/git-scm.com` at **depth 1** took
**254 s** and wrote **179 MB** into `.git`.

Read that as a bandwidth number, not a JGit number: 179 MB in 254 s is about
705 KB/s, which is what an emulator's NAT gives. JGit is not the slow part, and
nothing here suggests a native git would do better. But the number decides the
shape of the feature rather than whether it ships:

- clone cannot run on the main thread, or in an Activity scope that a rotation
  destroys;
- it needs a cancel that actually stops it, since a user who mistyped a URL
  will not wait minutes to find out;
- it needs a progress figure derived from JGit's `ProgressMonitor` rather than
  a spinner, because a spinner for four minutes is indistinguishable from a
  hang;
- and it needs to say what it is about to cost in **storage**, because 179 MB
  for one shallow clone of one repository is a number a phone's owner should
  see before it is spent, not after.

**Shallow does not mean small.** `--depth 1` bounds history, not content: this
repository is 179 MB at its tip. Anything the UI says about depth should avoid
implying otherwise.

This is the same shape `:toolchain:manager` already has for downloads and
`:engine:deps` has for a cold resolve — a foreground service or a
`WorkManager` job with reportable progress. It should reuse that machinery, not
grow its own.

## What M8 should take from this

1. **No spike work is owed before `:vcs:git`.** Depend on JGit directly, pinned,
   and write the module.
2. **Identity and credentials are ours to store**, because there is no global
   config and no credential helper. Keystore-backed, following `ApiKeyStore`.
3. **Long operations reuse the existing progress machinery.** Clone and fetch
   belong where `:toolchain:manager`'s downloads already are.
4. **Repositories live on internal storage**, for the same reason imported
   projects are copied there.
5. **SSH is unanswered.** HTTPS with a token is the designed path.

Delete this document's module (`:spike:git`) once `:vcs:git` answers the same
questions under its own tests. Keep this file.
