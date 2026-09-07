# `:toolchain:manager` — fetching what will not fit in the APK

`android.jar` is the first component this delivers and the one M2 could not be
finished without: the fast build engine cannot compile a line against the
framework without it, and at 27 MB it is not something to bundle in an APK for
every user whether they build or not.

---

## 1. The pin, and why it is a pin

Google publishes an SDK repository index at
`https://dl.google.com/android/repository/repository2-3.xml`. It is ~400 KB of
XML describing every package, each with a URL, a size and a **SHA-1**.

`ToolchainComponent.ANDROID_PLATFORM` records one entry from it by hand rather
than querying it at run time. An index is a moving target; a build engine whose
compile classpath silently changes under it is a support problem nobody can
reproduce. Moving to a newer platform should be a commit with a diff.

The values, verified against the real download:

| | |
|---|---|
| URL | `https://dl.google.com/android/repository/platform-36_r02.zip` |
| Size | 65,878,410 bytes |
| SHA-1 | `2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576` |
| Entry | `android-36/android.jar` (27,768,026 bytes) |
| Licence | `android-sdk-license` |

`ToolchainManagerTest` checks all of them against Google, and is skipped unless
`-Pandroid.testInstrumentationRunnerArguments.downloadTests=true` — they are
facts about someone else's server, and nothing else in the suite would notice
any of them going stale.

## 2. The whole archive has to be downloaded, even though 40% of it is wanted

The obvious optimisation is to range-fetch just `android-36/android.jar` out of
the zip — the central directory is at the end, dl.google.com honours ranges, and
it would save 37 MB of a phone's data.

It cannot be done, because the published checksum covers the **whole archive**.
Verifying the pin at all means having all of it. That trade is worth taking in
this direction: a truncated `android.jar` opens as a perfectly valid zip with
classes missing, and surfaces as compile errors against the user's own code with
nothing pointing at the platform.

## 3. A dropped connection does not always raise

Reading a fixed-length HTTP response short does not reliably throw — the stream
can simply report end of input. Unchecked, the short file goes on to fail its
checksum and be deleted as corrupt, throwing away the very bytes that resuming
exists to keep. The download compares the file's length against the pinned size
and raises itself.

**Resume is worth having here.** This is 63 MB over a phone connection; losing
it at 90% and starting from zero is the difference between a feature that works
on a train and one that does not. A failed download is therefore left on disk
deliberately, and only a *checksum* failure deletes it.

## 4. A server may answer a Range request with `200`

Ignoring `Range` and sending the whole body is legal. If the client assumes it
got a `206` and appends, it writes a second copy of the archive onto the partial
one, and the checksum then fails for ever with no way out but clearing app data.
The response code is checked, and a `200` restarts from zero.

Both this and the case above are covered by unit tests against a real
`HttpServer` on a real socket. A fake returning bytes through an interface would
exercise neither: they are properties of HTTP, not of the installer.

## 5. The licence is a precondition, not an onboarding screen

Downloading a platform is only permitted under the Android SDK Terms and
Conditions, so `ComponentInstaller` refuses without a recorded acceptance and
says so distinctly — `InstallProgress.Failed.licenseRequired` — so the UI can
offer the agreement rather than an error the user cannot act on. The text is
Google's own, taken verbatim from the repository index and shipped as
`R.raw.android_sdk_license`.

## 6. Components live in `filesDir`, not `cacheDir`

The system may clear a cache whenever it likes. A 63 MB download vanishing
between two builds is not a cache miss a user would forgive.

## 7. Things known missing

- **One component, no dependency graph.** The plan has the NDK sysroot (~400 MB)
  and the Kotlin compiler archive arriving the same way. Neither is modelled, and
  nothing expresses that one component needs another.
- **No update path.** A newer pin installs beside the old one; nothing notices
  the old one is now unused or offers to remove it.
- **No metered-connection check.** 63 MB should ask before it spends someone's
  data.
- **Nothing in `:app` composes this yet.** `DownloadedPlatformBuildTest` in
  `:engine:fast` proves the pieces fit -- download, stage the compile stubs,
  build -- but no screen offers the download, because there is no build screen
  yet. That is M1/M2 UI work.

## The Kotlin Analysis API component cannot be installed, and this is why

> **Resolved 2026-09-07** by rebuilding the archive and re-uploading it to the
> same release tag, then pinning to what is actually served. The component now
> installs on a device that has never had it: `gh release upload
> kotlin-analysis-2.2.10 ... --clobber`, then `archiveSha1` set to
> `9d1d1ae724af7afa4de325b622c5a806294d29eb` (the bytes were already right).
> `PinnedReleaseTest` passes for all five components, the download suite passes
> 12 of 12, and driving the app end to end puts `analysis-api.jar` and
> `analysis-backend.jar` into `files/toolchains/kotlin-analysis-api/`.
>
> The rebuilt archive was checked **before** upload for the thing that made a
> re-pin insufficient — `definitionAt` present in the backend dex — and the
> served bytes were downloaded and hashed **after** upload to confirm the pin
> describes what users get, rather than what was built locally. The account
> below is kept because the failure shape is the lesson, not the number.

**Found by driving the app**, on 2026-09-06, with everything else green: a full
sweep of 560 tests said nothing was wrong, because every test that uses this
component stages the archive by hand and never downloads it.

Opening a Kotlin file in a new project offers the Kotlin compiler (53 MB, which
installs correctly) and then the Analysis API (1 MB), and the second one fails:

```
The connection was lost while downloading Kotlin Analysis API 2.2.10.
Kotlin Analysis API 2.2.10 could not be downloaded (HTTP 416).      <- and for ever after
```

**One wrong number, three consequences.** `ToolchainComponent` pins
`archiveBytes = 1_991_075`; the published release asset is **1,988,723 bytes**
(`curl -sIL` on the release URL says so, and the partial left on the device was
exactly that long). `archiveBytes` was the completeness gate, so:

1. the whole file arrives, is 2,352 bytes shorter than the pin, and is reported
   as a lost connection;
2. the partial is kept, because keeping it is what makes resume work at all;
3. every retry asks for `bytes=1988723-`, which the server answers **416**, and
   nothing deletes the partial on a 416 -- so the component is unreachable until
   the user clears the app's data.

Two of those are now fixed and tested. **`Content-Length` decides completeness**
and the pin is only what the progress bar counts against; **the sha1 is the one
gate on correctness**, which it always was the only thing able to be. And a 416
discards the partial and starts over. `ComponentInstallerTest` covers both, and
both fail without the fix -- verified by reverting it.

**The third is not fixable from here.** The pinned sha1
(`9578660382…`) matches no published artifact either: the release holds
`47f6187b…`. And the published `analysis-backend.jar` is 40,642 bytes against
the current 43,132 -- it **predates `definitionAt`**, so it is not merely
mis-pinned, it is too old to use. Correcting the pin downward would ship a
component the app cannot drive.

**So the release asset has to be rebuilt and re-uploaded, and the pin
regenerated with it.** `tools/analysisapi/build-component.sh` prints the two
numbers to paste:

```
  archiveSha1  = "…"
  archiveBytes = …L
```

They must come from the artifact actually uploaded. A zip is not reproducible
here -- rebuilding identical source twice gives the same byte count and a
different sha1 -- so pinning a locally built archive and uploading a separately
built one produces exactly this failure.

**The gap was shaped exactly like the bug.** `ToolchainManagerTest` has a
download test for every component -- platform, build tools, compiler, Gradle,
the JDK, the native toolchain -- and all eleven pass against the real network.
The one component with no such test is the one that was broken.
`installs_the_kotlin_analysis_api_from_this_projects_releases` is the missing
sibling; it fails today, for the right reason, and it checks the archive is
*current* rather than merely valid, since the published one is a well-formed zip
whose backend has no `definitionAt`.

**And the gap was wider than the one component.** `ALL` — the list
`PinnedReleaseTest` walks — held only the five components with a fixed URL. The
per-architecture ones were absent, so the JDK's and clang's pins went unchecked
by the very test written because a pin shipped wrong, and those are the most
likely to drift: they are ours, rebuilt by hand, two files each. `ALL` now
expands both ABIs of every per-architecture component, which took it from five
entries to **thirteen**, all passing.

**The lesson worth keeping is about the test gap, not the number.** Every test
of this component stages its archive, which is right for speed and for working
offline, and means **nothing exercises the pin**. A test that fetches the real
release URL and checks its length and digest against the constant would have
caught this the day it drifted, and costs one request.


## 9. Measuring an install means not following its symlinks

Added 2026-09-07 with the Toolchains screen, which reports what each component
costs on disk so it can be removed.

`File.walkTopDown()` follows symlinks, and every toolchain here is built out of
them: Node ships `bin/npm` pointing into `lib/node_modules`, mono's `bin/mono`
points at `mono-sgen`, clang's driver names are all links to one binary. Walking
through them reported Node at **184 MB where `du` said 119** — a 55 %
overstatement on the one number that screen exists to show, and the kind of
error nobody checks because a plausible figure is indistinguishable from a
correct one. It was caught only by running `du` on the device beside the app.

Following them also risks a cycle, which would hang the measurement instead of
exaggerating it.

So the walk is hand-written with `Files.isSymbolicLink` as its first branch, and
a symlink contributes zero: the bytes it points at are already counted where
they live, or belong to something else. With that, Node measures 101 MB against
its pinned `installedBytes` estimate of ~104 MB — the pin was right all along.

**The listing is scanned from disk, not from `ALL`.** The entry worth the most
space is the one nothing claims any more: a component whose id changed leaves
its directory behind for ever, and a list built from what the app expects would
never mention it. Those are shown by directory name and marked "no longer used".
`SdkLicense`'s acceptance marker lives under the same root and is excluded --
offering to delete it would be offering to un-accept Google's terms from a
screen about disk space.
