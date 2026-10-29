# Vendored Material icons

The Kotlin files under `filled/` and `automirrored/filled/` are copied
**byte-identical** from AOSP's `material-icons-extended-android` 1.7.8 sources
jar (Apache License 2.0; each file keeps its header). They are the icons this
app uses that `material-icons-core` does not provide -- nothing else.

**Do not edit them by hand.** `tools/icons/vendor-icons.py` writes and prunes
them from the code's actual usage, and checks the sources jar against a pinned
SHA-256 before copying anything:

| artifact | sources jar SHA-256 |
|---|---|
| `androidx.compose.material:material-icons-extended-android:1.7.8` | `77b7e782a519a3e074fc91e09d59334ac1b815edd87d2e89c0c9ca7c95678e93` |
| `androidx.compose.material:material-icons-core-android:1.7.8` | `14db888d2f698178799a4c18a58bb134b3e98063c6748c443b339044e0145432` |

**Why.** The whole extended library was 13.7 MB of the app's 60.7 MB of dex
for about 57 icons out of 2,163. R8 would strip the rest only from a minified
build, and the previews people download are debug builds. The package names
are the library's own, so every call site resolves exactly as it did.

Using an icon that is not here yet fails to compile with "Unresolved
reference"; run the script.
