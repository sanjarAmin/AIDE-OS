# Spike R9: executing a downloaded native binary — result

**Outcome: it works, through the dynamic linker, and only from internal
storage.** M7's plan to ship clang as a download survives, but not by the route
anyone would try first, and not in the directory this project keeps projects in.

Measured on the `aideos_test` AVD (API 34, x86_64), test APK targeting SDK 37 —
W^X applies. Reproduce with `:spike:nativeexec:connectedDebugAndroidTest` and
read the table out of logcat under `NativeExec`.

## Why this was asked

`docs/PLAN.md` disagreed with itself, and M7 was built on the wrong half.

Line 122 records that Android 10+ enforces W^X and refuses to execute anything
under the app data directory, which is why `aapt2` ships inside `jniLibs` —
`nativeLibraryDir` is the one exempt place. Line 229 then plans clang, lld and
an NDK sysroot as *"an optional ~400 MB download"*, which lands in app data.

Both cannot be true, and 400 MB cannot go in `jniLibs`: that directory is fixed
at install time, and nobody wants those megabytes in the APK for a feature most
users never open.

## What the platform allows

| Route | Result |
|---|---|
| `/system/bin/toybox` directly (control) | **works** |
| Internal app data, `execve` directly | `error=13, Permission denied` |
| Internal app data, via `/system/bin/linker64` | **works** |
| Internal app data, via `/apex/com.android.runtime/bin/linker64` | **works** |
| External app storage, `execve` directly | `error=13, Permission denied` |
| External app storage, via `linker64` | `couldn't map … segment 3: Permission denied` |

The payload was `/system/bin/toybox` — a real dynamically linked executable
already on the device, copied to each location at mode 0700. The question is
where a binary may be run *from*, not which binary, so answering it needed no
toolchain download.

## 1. The linker is the route, and the asymmetry explains why

`execve` on a file under `/data/data` is refused outright. Handing the same file
to the dynamic linker works:

```
/system/bin/linker64 /data/user/0/…/toybox echo AIDE-OS-EXEC-WORKS
→ exit=0  AIDE-OS-EXEC-WORKS
```

The kernel execs `linker64`, which lives in an executable directory. The payload
is never `execve`d — it is opened and **mapped**. Internal app storage refuses
the first and permits the second, and that gap is the whole mechanism.

`docs/PLAN.md` R4 already listed "linker64 direct invocation" as a fallback exec
strategy. It was a guess until now.

## 2. External storage is not an option, and fails differently

Both routes fail there, and the *second* failure is the informative one:

```
error: couldn't map ".../toybox-external" segment 3: Permission denied
```

That is not `execve` being refused — it is `mmap` with `PROT_EXEC` being
refused, because the FUSE-backed mount forbids executable mappings entirely.
The linker trick cannot help, because the thing it relies on is exactly what is
blocked.

**This constrains M7 directly.** Projects live on external storage
(`getExternalFilesDir`) precisely because that survives updates and is reachable
over MTP. **A toolchain cannot live there.** It has to go in internal storage,
where the app's own quota applies and the user cannot reach it over USB — which
is a real cost for a ~400 MB payload and needs saying out loud before someone
designs a download that puts it in the wrong place.

## 3. `nativeLibraryDir` is a path, not a guarantee

It exists only when the APK actually ships a `.so`. This spike ships none, so
the directory is absent — a first version of the test asserted it existed and
failed for the right reason.

It is never writable at runtime, which is why a downloaded payload cannot simply
be placed there.

Both facts matter for M7: any design that execs from `nativeLibraryDir` needs
something in `jniLibs` for the directory to exist. `:app` already ships `aapt2`,
so it is fine; a separate toolchain-only APK carrying nothing else would not be.

## 4. What this does not answer

- **Whether clang itself runs.** This proves the *route*. clang is a much larger
  binary with its own dependencies, and `tools/kotlinc/FINDINGS.md` is a
  standing reminder that "it is just a binary" has been wrong here before.
- **Whether the linker route survives newer Android.** Measured on API 34. The
  gap it exploits — `noexec` on the mount, but `PROT_EXEC` mappings permitted —
  is not a documented guarantee, and could close. `docs/PLAN.md`'s manual device
  matrix exists for exactly this.
- **Argument-zero and `$0` behaviour.** Programs launched this way see the
  linker's own argv handling. Anything that re-execs itself, or inspects
  `/proc/self/exe`, may behave differently. clang drivers do both.
- **Performance.** Nothing here measures startup cost.

## What M7 should take from this

1. **Download the toolchain into internal storage**, not the external workspace.
   The workspace convention does not extend to executables.
2. **Exec through `/system/bin/linker64`**, not directly. The exec harness in
   `:toolchain:native` already exists for `aapt2`; this is a second strategy
   beside it, not a replacement.
3. **Spike clang specifically before designing around it**, per section 4.
4. **Budget the storage honestly.** ~400 MB of app-private data that the user
   cannot see, move, or reach over USB, and that counts against the app in
   Settings.
