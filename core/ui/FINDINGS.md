# `:core:ui` findings

## A feature can exist and be unreachable on one screen size

Found 2026-09-07 by resizing the emulator to a tablet and using the app.

The adaptive layout has two shapes: a phone gets a bottom dock with five tabs,
and a wide screen gets a permanent side pane. The side pane was **Build over
Git, stacked**, with a comment explaining the choice — those are the two a user
alternates between while finishing a change, and tabbing them would hide one
behind the other for no gain.

The reasoning is sound and the result was that **the terminal had no way in on
a tablet at all.** Problems and Logcat likewise. A shipped feature, reachable in
one tap on a phone, absent on a whole class of device — and invisible to every
test, because every test runs at the emulator's default size.

The pane keeps the build on top, because that is the thing you glance at rather
than work in, and the half below it is now tabbed: Git, Problems, Terminal.

**Check both shapes when changing either.** `adb shell wm size 1600x2560` and
`wm density 240` reach the wide layout on the standard AVD; `wm size reset` and
`wm density reset` undo it. The suite passes at both sizes, which is worth
knowing but is not the same as the layouts being right — only looking is.

## The extended icon library was a quarter of the app's code

Measured with `apkanalyzer dex packages` on 2026-10-29: of 60.7 MB of defined
dex, `androidx.compose.material.icons` was **13.7 MB, 23 %** -- the single
largest package, ahead of the Anthropic SDK (10.5 MB) and D8 (8.8 MB). The app
draws 80 icons; 23 come from `material-icons-core`, and the other 57 were the
reason the whole 2,163-icon extended library was a dependency.

R8 would remove the unused ones, but only in a minified build, and the
previews people download are debug builds. So the 57 are vendored
byte-identical into this module under their own package names -- call sites
unchanged -- by `tools/icons/vendor-icons.py`, from a sources jar pinned by
SHA-256. The APK went from 55.5 MB to 48.9 MB, the uncompressed dex from 130 MB
to 99 MB.

**The script reads imports, not call sites.** An icon is an extension
property, so it is reachable only through its import, and an import of an
icon nobody draws fails to compile just the same. The first version matched
`Icons.Filled.X` and missed an unused `InsertDriveFile` import in the
workspace screen; the build caught it, which is the guard working as intended.
