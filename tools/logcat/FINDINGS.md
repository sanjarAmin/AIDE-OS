# Reading the device log from an unprivileged app

Established 2026-09-07, because the dock's Logcat tab says "Nothing here is
wired up yet" and nobody had established whether it *could* be.

## 1. Without the permission, an app sees only itself

Run from AIDE-OS's own terminal, in the app's own uid:

```
logcat -d -t 4
09-07 09:05:47.716  9512  9532 D EGL_emulation: app_...
09-07 09:05:48.473  9512  9521 I com.osamu.aide: Com...
09-07 09:05:48.717  9512  9532 D EGL_emulation: app_...
09-07 09:05:50.216  9512  9532 D EGL_emulation: app_...
```

Every line is pid **9512**, which is AIDE-OS. Nothing from any other process.
So the tab's stated purpose — showing the log of *the app you just built and
installed*, which is a different uid — cannot be served this way.

That is the answer most people stop at, and it is wrong.

## 2. `READ_LOGS` is grantable, and the user grants it — on the device

`adb shell pm grant` refuses outright while the permission is undeclared:

```
java.lang.SecurityException: Package com.osamu.aide has not requested
permission android.permission.READ_LOGS
```

Declare `<uses-permission android:name="android.permission.READ_LOGS" />` and
run `logcat` again, and **Android itself asks the user**:

> **Allow AIDE-OS to access all device logs?**
> Device logs record what happens on your device. Apps can use these logs to
> find and fix issues. Some logs may contain sensitive info, so only allow apps
> you trust to access all device logs.
> **Allow one-time access** / **Don't allow**

Allow it, and the same command returns other processes:

```
09-07 09:08:14.911   751   751 I wpa_supplicant: wla...
09-07 09:08:22.482   762   762 D StatusBarIconContro...
09-07 09:08:35.650  1326  1365 D EGL_emulation: app_...
```

pids 751, 762 and 1326 — none of them ours. **The whole device log, with no
root, no adb and no desktop**, which is the constraint this project exists
under.

## 3. What that costs, and why it is not switched on

- **The consent is one-time.** The dialog offers "Allow one-time access" and
  nothing else, so it is per grant and must be asked for again. A Logcat tab
  has to expect the refusal and the re-ask, not treat consent as a setting.
- **The permission shows up whether or not it is used.** Declaring it means
  every install carries an app that *can* ask for all device logs, and the
  dialog tells the user to allow it "only [for] apps you trust". That is a
  trust decision about the product, not a technical one, so the manifest is
  unchanged and this document is the deliverable.

## 3b. Built 2026-09-09 — and section 2 did not reproduce

The permission is declared and the tab exists. Section 3's question was
answered by the user: declare it and build it.

What was seen driving the finished tab, on the API 34 emulator:

- The tab streams `logcat -v uid -v threadtime` into a monospace pane that
  tails, filters, pauses and clears. That part works.
- **Android never showed the consent dialog.** Section 2 records it appearing
  as soon as the permission was declared; it did not here, and
  `dumpsys package com.osamu.aide` reported
  `android.permission.READ_LOGS: granted=false` throughout.
- `adb shell pm grant com.osamu.aide android.permission.READ_LOGS` now succeeds
  (section 2 is right that it refuses while undeclared) and reports
  `granted=true`. **The tab still saw only its own lines** after a restart.

So on this device an app that `execve`s `/system/bin/logcat` gets logd's own
uid filtering and no dialog, whichever way the permission reads. The likely
difference from section 2 is *how* the log was asked for: that observation came
from typing `logcat` into the app's terminal, which is the same binary but a
different process tree, and it is worth re-running before trusting either
result. A framework path rather than the binary may be what raises the dialog.

Until that is settled the tab is honest rather than capable: it detects the
case and says so, in the panel, with a button to ask again.

**Detecting it is a uid comparison, and pid is the trap.** The obvious signal
-- a line whose pid is not ours -- is wrong, because the log buffer holds this
app's *own earlier processes*. A restarted AIDE-OS sees a dozen foreign pids
that are all itself and concludes it has access it does not have; that shipped
for one build and was caught by driving it. `-v uid` adds the column that is
per app rather than per run, and `LogcatLineTest` pins the parse, including
that a named uid (`wifi`, `system`) comes back as its name.

## 4. Filtering to the built app is the remaining unknown

`logcat --pid=` needs a pid, and an unprivileged app cannot resolve another
package's pid — `/proc` is hidden. The workable shape is a text filter over the
stream, defaulted to the project's `applicationId`: a crash carries the process
name in `AndroidRuntime`'s message, which is the case a Logcat tab exists for.
Nothing here has tried it, because §3 has to be decided first.
