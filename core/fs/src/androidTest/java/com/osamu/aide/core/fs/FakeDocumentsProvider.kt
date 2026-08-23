package com.osamu.aide.core.fs

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/**
 * A DocumentsProvider over a real directory, so the SAF import path can be
 * tested for what it actually is.
 *
 * There is no way to obtain a tree URI in a test the way a user does -- the
 * picker requires a human -- and no fake ContentResolver worth having. Serving
 * a temp directory through the real framework is the only version of this test
 * that exercises the cursor columns, the recursion and the stream opening
 * rather than a mock of them.
 *
 * Document ids are paths relative to [root], which is all the test needs and
 * is not how a real provider should identify documents.
 */
class FakeDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf())

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(COLUMNS).apply { add(this, fileFor(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(COLUMNS).apply {
        fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { add(this, it) }
    }

    /**
     * Not optional, and the default is false.
     *
     * Every buildDocumentUriUsingTree call is checked against this before the
     * provider is asked for anything, so without it the framework rejects each
     * child with "Document src is not a descendant of root" and the import sees
     * an empty tree.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId)
        return generateSequence(fileFor(documentId).parentFile) { it.parentFile }
            .any { it == parent }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        fileFor(documentId),
        ParcelFileDescriptor.MODE_READ_ONLY,
    )

    private fun add(cursor: MatrixCursor, file: File) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentIdFor(file))
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(
                Document.COLUMN_MIME_TYPE,
                if (file.isDirectory) Document.MIME_TYPE_DIR else "text/plain",
            )
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    private fun fileFor(documentId: String): File =
        if (documentId == ROOT_DOCUMENT_ID) root else File(root, documentId)

    private fun documentIdFor(file: File): String =
        if (file == root) ROOT_DOCUMENT_ID else file.relativeTo(root).path

    companion object {
        const val AUTHORITY = "com.osamu.aide.core.fs.test.documents"
        const val ROOT_DOCUMENT_ID = "root"

        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * Set by the test before the provider is queried. Static because the
         * framework instantiates the provider, so nothing can be passed in.
         */
        lateinit var root: File
    }
}
