# `jspawnhelper`, so the JVM can start a process

`libjspawnhelper.so` is the JDK's own `jspawnhelper`, taken unmodified from
`lib/jvm/java-21-openjdk/lib/jspawnhelper` in Termux's `openjdk-21` package
(21.0.12) — the same package `tools/rootfs/fetch-jvm.sh` downloads. Rebuild it
with:

```sh
tools/rootfs/fetch-jvm.sh <staging> aarch64
tar xf <staging>/jvm.tar -C . ./lib/jvm/java-21-openjdk/lib/jspawnhelper
```

**Why it is here.** The JVM's default way of starting a process
(`POSIX_SPAWN`) runs this helper and has *it* exec the target. It lives inside
the JDK, in app-private storage, which may not be executed — so every
`ProcessBuilder` in a build fails with `Failed to exec spawn helper`, a message
that names neither the helper's location nor the reason.

Shipping it in `jniLibs` puts a copy in `nativeLibraryDir`, the one directory an
app may execute from. The JDK's own copy is then symlinked at it, exactly as
`bin/java` and `aapt2` are. `tools/rootfs/FINDINGS.md` §5 and §7.

**It is renamed, not modified.** Only files matching `lib*.so` are packaged into
`jniLibs`, which is why an executable is called that — the same trick
`:toolchain:native` uses for aapt2.
