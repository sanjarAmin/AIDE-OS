# `:vcs:git` — findings

Spike R6 (`tools/git/FINDINGS.md`) established that JGit runs on ART unmodified
and what that costs. This file records what was learned building a module on
top of it — mostly JGit behaviours that are not what the command line taught
everyone to expect, each measured rather than reasoned about.

**Read `tools/git/FINDINGS.md` first.** Its finding 1 is the reason this module
exists at all rather than being a few JGit calls at the call site: a device has
no user home and no system config, so identity and credentials have nowhere to
come from but here.

## 1. `add` already stages deletions

This module originally staged in two calls — `add`, then `add` with
`setUpdate(true)` — on the strength of the familiar rule that `git add <path>`
records content and `git add -A` records absence.

**Measured: JGit does not work that way.** Staging a path whose file is gone
moves it straight to `removed`:

```
after plain add of a deleted file: removed=[a.txt] missing=[]
```

The second call was dead code, and the comment justifying it was folklore. It
is gone, and `GitRepositoryTest.staging_records_a_deletion` now pins the real
behaviour — which matters more than the code did, because if a future JGit
changes its mind the test is the only thing that will notice.

Worth generalising: **git lore is about the C implementation.** JGit is a
reimplementation with its own choices, and this module should check rather than
assume every time the two could differ.

## 2. A local clone reports no progress at all

JGit drives its `ProgressMonitor` from the *transport*. Over `file://` there is
effectively none, so a local clone completes without a single `beginTask` —
even for a repository of several hundred objects, which was tried.

That has one direct consequence: **cancellation cannot be tested without the
network.** Cancelling a clone works by the monitor answering `isCancelled`
between work units, and a monitor that is never called cannot answer anything.
So `GitWorkspaceTest` clones a real (tiny) repository for that one test, and
this module's `androidTest` manifest carries `INTERNET` for it.

The mechanism itself is tested separately and hermetically:
`cancelling_the_scope_cancels_the_monitor` drives `CoroutineProgressMonitor`
directly. Both are needed — one proves the bridge, the other proves it is wired
in.

## 3. JGit cleans up only a directory it created

On a failed or cancelled clone JGit removes what it wrote. Measured, with a bad
host and with a cancelling monitor:

| target | result |
|---|---|
| did not exist | `exists=false` |
| pre-created, empty | `exists=true`, `entries=[]` |

So the contents are always cleaned and the **directory itself survives when the
caller made it**. That case is reachable here — `GitWorkspace.clone` accepts an
existing empty directory, which is exactly what a file picker produces when the
user creates a folder before choosing it — so the module deletes the leftover.

This cost two rounds of mutation testing to get right. The first version of the
test let JGit create the directory, so it passed with the module's own cleanup
removed: it was asserting that JGit works. **Pre-creating the directory is what
makes the assertion about this module.**

## 4. A rejected push does not throw

JGit reports a non-fast-forward, or a hook refusing the update, through
`RemoteRefUpdate.Status` on the returned result. A push that changed nothing on
the far side returns normally, so `"it did not throw"` is not evidence and
neither is an `OK` in the result object on its own.

`GitRepository.push` inspects every update and treats anything outside
`{OK, UP_TO_DATE}` as a failure. The test asserts on the **receiving**
repository — resolving the pushed commit in the bare repo — rather than on the
push result, for the same reason.

## 5. A token goes in the password field, with any username

Hosting providers differ on where a personal access token belongs, but every
major one accepts it as the *password* with a non-empty username. So that is
what is sent, with a constant username (`aide-os`) rather than the user's
account name — which would be one more thing to store, one more thing to get
wrong, and is ignored anyway.

A token is only *required* for `http(s)` remotes. A `file://` remote has no
host to key one on, and demanding one for it would refuse a push that would
have worked. That is not hypothetical: it is how the push test runs.

## 6. Validate what will be stored, not what was typed

`GitIdentity.validate()` originally checked the raw strings while
`GitIdentityStore.save()` stored the trimmed ones. A padded but perfectly good
address was rejected by validation that the store would have accepted —
`"  ada@example.com  "` fails "no spaces in an email address".

Caught by a test asserting the round trip, not by review. **Where a store
normalises, validation has to normalise first**, or the field refuses what the
store would take.

## 7. A reload that clears the error clears the one you just set

Not a JGit finding, but it happened **twice** in one milestone and both times
the symptom was a failure with no message anywhere.

Both view models follow the same shape: do something, then reload so the screen
matches what is now on disk. Both reloads set `errorMessage = null` on success,
because a successful load means nothing is wrong. And both operations set an
error *before* reloading, so the reload erased it.

- `ProjectsViewModel`: cloning a repository with no Android module reported
  "Cloned, but ..." and then `refresh()` wiped it. The user saw a clone that
  finished and produced nothing.
- `GitViewModel`: a commit refused for having no identity set the error, and the
  reload after it cleared it. The commit silently did not happen.

Two different fixes, because the right one differs. `ProjectsViewModel` awaits
the reload and then reports, so the message is last. `GitViewModel.reload`
instead only replaces `errorMessage` when the read it just did actually failed,
because it is called after every operation and cannot know which.

**The rule worth keeping: a refresh may clear only errors it is in a position
to have resolved.** Anything else it must leave alone.

## 8. Four bugs the tests could not see

Every one of these was found by installing the app and using it, and every one
was invisible to a suite that drives view models. Recorded together because the
pattern is the finding.

**The git panel was unreachable.** The tool dock opened only when a build
started, which was right while it held nothing but build output. It now holds
git too, so committing required building first. The dock has its own toolbar
button now.

**A created project could never become a repository.** The panel correctly said
"not a git repository" and offered nothing further. `GitWorkspace.init` had
existed since the module was written and nothing called it.

**The changed-files list was laid out at zero height.** The dock is a fixed
200dp, sized for build output; the git panel's branch line, identity warning and
commit field nearly fill that on their own. The list was `weight(1f, fill =
false)`, so it took only the height it was given -- which was none. A repository
with three untracked files showed one, clipped, overlapping the row beneath it.

The route to seeing that was `adb shell uiautomator dump`, which shows the
rendered tree with bounds. The screenshot showed blank space; the dump showed a
`Stage aide.json` node with bounds overlapping the identity row, which named
the problem immediately.

**The wrong field turned red.** Identity validation returned one message for the
pair, so an empty *email* marked the *name* as an error. `nameProblem()` and
`emailProblem()` are now separate and `validate()` composes them, so a caller
that wants one message and a caller that wants to mark one field cannot
disagree.

**None of these are logic errors**, which is why the tests missed them: three
are layout and one is attribution. A view-model test asserts what the state
says, and every one of these had correct state.

## 9. Staged and unstaged are two different diffs

`diff(path, staged)` takes a flag rather than producing one answer, because
there are genuinely two: the working tree against the index, and the index
against `HEAD`. A file can have both at once — edit, stage, edit again — and
they show different content.

A panel that showed one while labelling it the other would be wrong half the
time and look right every time, so the dialog says which it is and the test
asserts both from the same file in one go: staged shows `first -> second` and
must *not* contain the unstaged `third`, and vice versa. Either assertion alone
passes against an implementation that ignores the flag.

**The first commit has no `HEAD` to compare against.** An empty tree is the
correct other side there — everything staged is an addition — and getting it
wrong throws on the very first commit a user makes, which is the worst possible
moment. It has its own test.

**Diffs are bounded at 256 kB.** A generated file or a committed binary
produces text no phone can show and that costs real memory to hold; ART's
default heap is 192 MB. Past the bound the text is cut and says so, rather than
the caller meeting the limit as an `OutOfMemoryError`.

## 10. Things known missing

- **No merge, rebase or pull.** `fetch` is deliberately separate: fetching is
  safe and can run in the background, merging can conflict, and a background
  operation that leaves a working tree in conflict is a trap. What to do with
  what arrived is not decided yet.
- **Diff has no syntax colouring and no word-level highlighting.** It is the
  unified text JGit produces, shown monospaced. Adequate to decide what to
  commit, well short of a review tool.
- **No branch operations.** No create, switch, delete or list. `currentBranch`
  reads; nothing writes.
- **No SSH.** Spike R6 left it deliberately unanswered; `org.eclipse.jgit.ssh.apache`
  is a separate artifact and a separate key-management problem. HTTPS with a
  token is the designed path.
- **No submodules, no LFS, no sparse checkout, no `deepen`** — a shallow clone
  cannot yet be completed into a full one.
- **No `.gitignore` awareness beyond what JGit does for `status`.** Nothing
  helps a user write one, and an Android project without one commits `build/`.
- **No history browser.** The panel shows the last ten commits as one line
  each. There is no way to see a commit, let alone what it changed.
- **No conflict resolution.** `GitStatus.conflicting` is reported and blocks a
  commit; nothing helps the user out of that state, and without `pull` there is
  currently no way into it either.
- **Push has no upstream handling.** It pushes the current branch to a
  same-named branch on `origin` and reports a rejection. It cannot set an
  upstream, force-push, or push a branch that does not exist on the remote yet
  under a different name.
