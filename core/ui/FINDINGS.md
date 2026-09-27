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
