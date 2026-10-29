# `:app` — findings

The app shell has mostly been a consumer of what other modules found. This
file records what was established in `:app` itself, starting with the app
updating itself — each point measured on the `aideos_test` emulator (API 34)
against this repository's real GitHub releases, on 2026-10-29.

The code is `com.osamu.aide.update`; `ReleaseFeedTest` pins the GitHub side.

## 1. GitHub's "latest release" cannot see an app preview

Every app preview is published as a **pre-release**, and
`GET /repos/{owner}/{repo}/releases/latest` skips pre-releases by definition.
It answered with `python-3.14.6` — a toolchain release from the same
repository — which a check built on it would have compared against and found
nothing to offer, for ever, while reporting success.

So the check lists releases (`?per_page=50`, newest first) and filters by the
`app-preview-` tag prefix, skipping drafts and any preview without an APK
attached. Toolchain releases share the repository and must keep their own
prefixes; a release whose tag began `app-preview-` without being one would be
offered as an update.

## 2. "Newer" is asked of git, because the version number cannot answer it

Before this work every preview was `versionCode 1`. `versionCode` is now the
commit count on `main` (`app/build.gradle.kts`), which Android needs to accept
an update — but it still cannot say whether a *published* build is newer than
the running one: a branch can count higher than `main` without containing it,
and nothing before the change carries a usable number.

GitHub's compare endpoint answers the real question.
`compare/<installed>...<published>` returns `status`:

| status | meaning here |
|---|---|
| `ahead` | the published commit contains the running one — offer it, with `ahead_by` |
| `identical`, `behind` | nothing newer is published (`behind` is a local build ahead of every preview) |
| `diverged` | the running build is on a branch previews do not follow — say so, offer nothing |
| HTTP 404 | the running commit is not on GitHub (unpushed) — cannot tell, say so |

Measured: `012c620...147a159` → `ahead`, `ahead_by: 2`; reversed → `behind`;
an unknown sha → 404. Short and full shas both work.

## 3. Asset digests are published, so every download is verifiable

The releases listing carries `"digest": "sha256:<hex>"` on each asset. The
check requires it — falling back to the `SHA-256:` line every preview's notes
end with — and **refuses to download** a release that publishes neither. An
APK that arrives and does not match is deleted before anything can install it.

`digest` can be JSON `null`. On a phone, `optString` reads that as the text
`"null"` (the JVM's org.json reads `""`), which would have become a checksum
nothing could match. Read through `isNull`, as `ai/core/FINDINGS.md` §16 says.

## 4. Installing over yourself works, and ends your process

`ApkInstaller` — the same PackageInstaller session the build engine uses for
the apps it builds — installs the app's own package without change. The
system's dialog says "Do you want to update this app?", and the installed
`base.apk` hashed byte-identical to the release asset afterwards.

What a caller has to know:

- **The success broadcast is never heard.** Replacing the package kills the
  process that is waiting for it. The UI therefore says *before* the dialog
  that the app will close and has to be reopened from the launcher; after it,
  there is nothing left to say it.
- **The first update usually fails after downloading**, because an app
  installed from a file or by adb may not install others until the user allows
  it in Settings. `InstallStatus.Failed.settings` carries that intent. On
  return, Try again found the verified download still on disk and went straight
  to the dialog rather than fetching 56 MB again.
- **Cancel reads as "INSTALL_FAILED_ABORTED: User rejected permissions".** That
  is the platform's status message on API 34 for a tap on Cancel. `ApkInstaller`
  prefers platform messages as more specific, except for this one: it now says
  "Installation was cancelled."

## 5. Every preview before versioning is now a downgrade

Previews up to `app-preview-147a159` are `versionCode 1`; anything built since
counts commits (311 at the time). Android refuses to install a lower
`versionCode` over a higher one, so **an older preview cannot be reinstalled
over a newer build** — uninstalling first is the only route, and it deletes
the app's projects (CLAUDE.md, "A `connectedAndroidTest` run uninstalls the
app"). The update path checks the downloaded APK's `versionCode` against the
installed one first and says this in words, rather than letting the installer
fail with a package-manager constant.

The commit count also needs a **full clone** wherever a release is built: a
shallow clone counts too few commits and produces a lower `versionCode` than
the build it is meant to replace.

## 6. Driving the update path before a release that has it

The first release that can update itself cannot be tested by updating to it.
`./gradlew :app:assembleDebug -Paide.pretendBuiltFrom=<older sha>` builds an
APK that believes it is the preview at that sha, at `versionCode 1`, so on a
device it finds the previews published since and installs the newest over
itself. That is how sections 1–4 were measured. Clear the app's data first
(`pm clear`) or the daily check will not be due. Never publish such a build.

## 7. What F-Droid inclusion takes, which is more than metadata

Read against F-Droid's inclusion policy (f-droid.org/docs/Inclusion_Policy)
on 2026-10-29. Not yet tried against their build server; what follows is what
the policy says, set against what this repository is.

- **The self-updater has to go in an F-Droid build.** The policy counts an
  app's own update mechanism as downloading executable code, allowed only
  opt-in and only if declining is no harder than accepting. It would also be
  useless: F-Droid signs with its own key, so a GitHub preview cannot install
  over an F-Droid install. `-Paide.distribution=fdroid` builds without it, and
  About says updates come from F-Droid.
- **Toolchain downloads already have the consent the policy asks for.** Every
  compiler, runtime and SDK platform is offered in a dialog and fetched only on
  acceptance -- the policy's condition for runtime executables. Each must stay
  that way: a component fetched silently would be an undisclosed anti-feature,
  which is a rejection.
- **Every committed binary has to be rebuilt by F-Droid from source.** "All
  binary dependencies including JAR files must originate either from source
  compilation or Debian repository downloads." This repository commits
  `toolchain/native/src/main/jniLibs/*/libaapt2.so` (4.5 MB each, AOSP),
  `libjspawnhelper.so`, `editor/src/main/jniLibs/*/libtree-sitter-javascript.so`
  and `engine/fast/src/main/assets/platform-stubs.jar`. The scripts that make
  them are in `tools/`; an F-Droid recipe has to delete the committed copies
  and run those scripts on their build server, and `tools/aapt2` builds from
  an AOSP checkout. **This is the real work of getting into F-Droid.**
- **Maven dependencies are fine as they are** -- Maven Central and Google's
  repository are trusted sources -- and nothing here uses Play Services or a
  tracker.
- **The commit-count versionCode needs a full clone** (§5). F-Droid's recipe
  states each version's code explicitly, which has to match what the build
  computes from the history it checks out.
