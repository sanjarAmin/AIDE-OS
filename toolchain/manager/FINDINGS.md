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
