# `:core:fs` — findings

## 1. A SAF tree cannot back a buildable project

This is the constraint that shapes the whole import feature, and it is not
obvious until you try.

The Storage Access Framework hands out `content://` URIs, not paths. Everything
inside this process can work with them. `aapt2` cannot: it is a native
executable in a separate process, it takes filesystem paths on its command
line, and a content URI means nothing to it. Neither does one mean anything to
`javax.xml.parsers` reading a manifest off disk, or to ECJ's classpath.

So a project opened in place from SAF would be editable and unbuildable — the
worst of the two options, because the user would only find out at the point
they tried to build. `ProjectImporter` copies the chosen tree into the
workspace instead, after which it is an ordinary project and everything
downstream is unchanged.

The copy is bounded rather than apologised for: `build/`, `.git/`, `.gradle/`,
`.idea/` and `node_modules/` are skipped — between them most of a checked-out
project's bytes — and an import over 200 MB is refused with the reason.

**If this is ever revisited**, the thing to check is whether aapt2 can be given
file descriptors instead of paths. It cannot today; the AOSP sources take paths
throughout.

## 2. A `DocumentsProvider` must be exported

`DocumentsProvider.attachInfo` throws `SecurityException: Provider must be
exported` if the manifest says otherwise — even for a test-only provider that
nothing should reach. What actually guards it is
`android:permission="android.permission.MANAGE_DOCUMENTS"`, which is
signature-level and held only by the system, so the provider's own app is the
only thing that can query it.

This is why `core/fs/src/androidTest/AndroidManifest.xml` declares
`exported="true"` on a provider that is not meant to be public.

## 3. `isChildDocument` defaults to false, and tree URIs need it

Every `DocumentsContract.buildDocumentUriUsingTree` call is checked against the
provider's `isChildDocument(parent, child)` before the provider is asked for
anything. `DocumentsProvider`'s default implementation returns false, so a
provider that does not override it serves the root document and nothing else —
and the caller sees an *empty tree*, not an error.

The symptom is an import that "works" and produces an empty project.

## 4. A provider may ignore the projection you asked for

`ContentResolver.query` takes a projection, and nothing makes the provider
honour it: a `MatrixCursor` built with all its columns returns all of them
whatever was requested. Reading `cursor.getString(0)` as the document id
therefore works against some providers and silently reads the wrong column
against others.

`ProjectImporter` looks every column up by name with `getColumnIndexOrThrow`.
`COLUMN_SIZE` is documented as optional and is looked up with `getColumnIndex`,
treating absent as zero — which only means the size check under-counts a
provider that does not report sizes.

Found by the fake provider in the tests, which is legal in exactly this way.
