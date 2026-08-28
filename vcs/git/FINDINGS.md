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

## 8. Things known missing

- **No merge, rebase or pull.** `fetch` is deliberately separate: fetching is
  safe and can run in the background, merging can conflict, and a background
  operation that leaves a working tree in conflict is a trap. What to do with
  what arrived is not decided yet.
- **No diff.** `GitStatus` names files; nothing produces a hunk. The editor
  needs this before a commit screen is worth using.
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
