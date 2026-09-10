# Debugging another app from an unprivileged app

Spike R15, 2026-09-09. `docs/PLAN.md` lists `:debugger` as "JDWP client for
on-device Java/Kotlin debugging". This asks the permission question before any
protocol code is written, because every milestone here that looked like a
library problem was a platform-permission problem first: clang could not spawn,
Node's `execPath` was the linker, the Logcat tab saw only its own process.

Probed **in an app's own process**, not through `adb shell` or `run-as`.
`tools/clang/FINDINGS.md` §7 records this project trusting a `run-as` probe and
being wrong; `run-as` runs in `runas_app`, which is a different SELinux domain
from `untrusted_app`, and `shell` is different again.

## 1. `@jdwp-control` is not reachable from an app — closed

`/proc/net/unix` shows it bound and listening, so the name is real:

```
0000000000000000: 00000003 00000000 00000000 0005 03 335769 @jdwp-control
```

Connecting from an app gets `IOException: Connection refused`.

Worth reading precisely: **refused, not denied**. An SELinux denial on an
abstract socket surfaces as `EACCES`/`EPERM`; "connection refused" is the
socket declining the connection. Either way the route is shut, and it is shut
without a message that names a permission to ask for — so there is nothing to
put in front of the user.

An app also cannot enumerate what is there. `/proc/net/unix` is unreadable from
an app on this API level: the probe that counted `jdwp` lines returned −1, and
`/proc/net/tcp` likewise shows only the caller's own sockets. So an app cannot
even discover which processes are debuggable.

## 2. adbd over TCP is reachable — on an emulator, and only as far as connect()

An app with `INTERNET` connects to `127.0.0.1:5555` successfully.

**Two caveats, and both matter more than the result.**

*It is an emulator.* `emulator-5554` means console 5554 and **adbd on TCP 5555
by default** — that is how the host talks to it. A physical device has no TCP
listener until the user turns on wireless debugging, and then the port is
randomised and the connection must be paired. Nothing here has been run on a
phone; that is the first thing to repeat when one is available.

*Connecting is not authenticating.* All that was established is that the socket
accepts. adbd then requires an RSA challenge the user authorises on-screen, and
wireless debugging adds TLS pairing with a six-digit code. Whether an app can
complete either is **unknown and is the next question** — it is what decides
whether this route is real. Do not read "CONNECTED" as "debugging works".

*And the first run of this probe was wrong.* It reported
`SocketException: socket failed: EPERM` and looked like the platform refusing
an app access to adbd. The spike module simply had no `INTERNET` permission, so
`socket()` failed before any connection was attempted. A missing permission and
a policy denial are indistinguishable from the message.

## 3. A JVMTI agent attaches to the calling process — open

`Debug.attachJvmtiAgent` with a path that does not exist fails with:

```
IOException: Unable to dlopen /data/.../no-such-agent.so: library not found
```

It reached `dlopen`. The mechanism is **not** closed off — only the agent was
missing. The process was debuggable (`FLAG_DEBUGGABLE`), which is what a test
APK and a debug build both are.

This cannot attach to another app: the agent loads into the caller. But AIDE-OS
controls what it builds, so a stub linked into a debug build could open a
channel from *inside* the debuggee to the IDE.

## 4. Somebody has already answered the open question — Shizuku

**CodeOnTheGo** (appdevforall, GPL-3.0, the other AndroidIDE fork) ships an
on-device debugger and has described how. From its Show HN thread: they
*"attach the JDWP agent to the target process at launch and route its output to
our debugger over a local socket"*, using *"a scoped adaptation of the
[Shizuku](https://shizuku.rikka.app/) project to get the necessary system
access without requiring root."*

That is §2's branch, taken and working. Shizuku's whole trick is to run a
helper process **as `shell`**, started either by root or — the part that
matters here — by the user pairing over **wireless debugging on the device
itself**, no PC involved. From `shell` the things an app cannot do become
available: `am set-debug-app`, reading `/proc`, and reaching JDWP.

So the answer to "can an unprivileged app debug another app" is: *not as
itself*, but **yes through a shell-privileged helper the user grants once**.
The connection §2 observed was real; what was missing was the authentication,
and pairing is how that is done.

This changes the recommendation below. It does not remove the phone test — what
still needs measuring is what the pairing flow costs a *user* (how many steps,
how often it must be redone, what happens across reboots), because that cost is
the feature's real price and it cannot be judged from an emulator.

## What this makes `:debugger`

Routes 1 and 3 say the module is **not a JDWP client attaching to arbitrary
processes on its own**. That shape is unavailable to an unprivileged app, and
`docs/PLAN.md`'s one-line description assumes otherwise.

Two shapes remain, and §4 makes the first the likely one:

1. **A shell-privileged helper, Shizuku-style.** The user pairs once over
   wireless debugging; the helper launches the debuggee with a JDWP agent and
   pipes it back. Debugs any debuggable app, matches what CodeOnTheGo does, and
   costs the user a pairing flow to be measured on hardware.
2. **A stub in the build template.** A debug build AIDE-OS produces carries an
   agent that opens a channel from inside. No pairing, no privileged helper,
   and it only ever debugs apps built here — which for this product is close to
   the whole requirement.

They are not exclusive: (2) is a smaller first step that works with no user
setup at all, and (1) is the upgrade that generalises it. Starting with (2)
would give a working debugger for the case that matters while the pairing cost
of (1) is being measured.

Order of work:

1. On a phone: run the wireless-debugging pairing flow by hand and count what
   it asks of the user. That is the input the choice above needs.
2. Build (2) far enough to step a line in an app AIDE-OS built.
3. Decide whether (1) is worth its setup cost, with both numbers in hand.
