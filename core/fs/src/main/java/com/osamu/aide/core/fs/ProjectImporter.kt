package com.osamu.aide.core.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Brings a project in from anywhere on the device, through the Storage Access
 * Framework.
 *
 * **Importing copies.** It would be neater to open the chosen folder in place,
 * and it cannot be done: the build engine execs aapt2, a native binary that
 * takes filesystem paths, and a SAF document has no path -- only a content://
 * URI that means nothing to a process outside this app. A project left where
 * the user picked it would be editable and unbuildable, which is the worst of
 * both. So the tree is copied into the workspace and is then an ordinary
 * project. See editor/FINDINGS.md.
 *
 * The cost is honest and bounded: `build/`, `.git/` and friends are skipped,
 * and an import larger than [maximumBytes] is refused rather than quietly
 * filling the device.
 */
class ProjectImporter(
    private val context: Context,
    private val workspaceRoot: File,
    private val dispatchers: DispatcherProvider,
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
) {

    /** One node of the picked tree, read before anything is copied. */
    private class Node(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val children: MutableList<Node> = mutableListOf(),
    ) {
        fun child(name: String): Node? = children.firstOrNull { it.name == name }

        fun bytes(): Long =
            if (isDirectory) children.sumOf { it.bytes() } else size
    }

    suspend fun import(tree: Uri): AppResult<Project> = withContext(dispatchers.io) {
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = read(tree, rootId, name = displayName(tree, rootId), depth = 0)

            val module = moduleIn(root)
                ?: return@withContext failure(
                    "\"${root.name}\" is not an Android module. Pick the folder that " +
                        "contains src/main/AndroidManifest.xml, or one whose only " +
                        "subfolder does.",
                )

            val bytes = module.bytes()
            if (bytes > maximumBytes) {
                return@withContext failure(
                    "\"${module.name}\" is ${bytes / MEGABYTE} MB. Imports are limited to " +
                        "${maximumBytes / MEGABYTE} MB; build output and .git are already skipped.",
                )
            }

            // Named after what the user picked, not after the module inside it.
            // Descending into a Gradle root's only module is a convenience;
            // calling the result "app" would hide which project it came from,
            // and a workspace of projects all called "app" is unusable.
            val name = root.name
            val target = File(workspaceRoot, ProjectDescriptor.directoryNameFor(name))
            if (target.exists()) {
                return@withContext failure("A project folder named \"${target.name}\" already exists.")
            }

            workspaceRoot.mkdirs()
            try {
                copy(tree, module, target)
            } catch (failure: Exception) {
                // A half-copied project is worse than none: it would list in the
                // projects screen and fail to build for reasons that look like
                // the user's fault.
                target.deleteRecursively()
                throw failure
            }

            val project = describe(name, target)
            ProjectDescriptor.write(project)
            AppResult.Success(project)
        } catch (failure: Exception) {
            AppResult.Failure(
                AppError(failure.message ?: "The folder could not be imported.", failure),
            )
        }
    }

    /**
     * The module to import: the picked folder, or its only child that looks
     * like one.
     *
     * The second case is the common one. People pick the folder they cloned,
     * which is a Gradle root holding `app/`; refusing that and making them
     * navigate one level deeper would be technically correct and useless.
     * Ambiguity is refused rather than guessed at -- with two modules there is
     * no right answer, and picking one silently is worse than asking.
     */
    private fun moduleIn(root: Node): Node? {
        if (root.isModule()) return root
        return root.children.filter { it.isDirectory && it.isModule() }.singleOrNull()
    }

    private fun Node.isModule(): Boolean =
        child("src")?.child("main")?.child("AndroidManifest.xml") != null

    private fun read(tree: Uri, documentId: String, name: String, depth: Int): Node {
        val node = Node(documentId, name, isDirectory = true, size = 0)
        // SAF trees cannot contain cycles, but a bad provider is not this app's
        // problem to crash over.
        if (depth >= MAX_DEPTH) return node

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            // By name, not by position. A provider is free to return columns it
            // was not asked for, and reading index 0 as the document id then
            // gives an empty project rather than an error.
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val childId = cursor.getString(idColumn)
                val childName = cursor.getString(nameColumn) ?: continue
                val isDirectory =
                    cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR

                if (childName in SKIPPED || childName.startsWith(".")) continue

                node.children += if (isDirectory) {
                    read(tree, childId, childName, depth + 1)
                } else {
                    // SIZE is documented as optional. Unknown counts as zero,
                    // which only means the size check under-counts a provider
                    // that does not report it.
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else {
                        0L
                    }
                    Node(childId, childName, isDirectory = false, size = size)
                }
            }
        }
        return node
    }

    private fun copy(tree: Uri, node: Node, target: File) {
        check(target.mkdirs() || target.isDirectory) { "Could not create ${target.absolutePath}" }
        node.children.forEach { child ->
            val destination = File(target, child.name)
            if (child.isDirectory) {
                copy(tree, child, destination)
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)
                context.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "${child.name} could not be read." }
                    destination.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun describe(name: String, rootDir: File): Project {
        val layout = ProjectLayout(rootDir)
        return Project(
            name = name,
            rootDir = rootDir,
            applicationId = packageOf(layout.manifestFile)
                ?: "com.example." + ProjectDescriptor.directoryNameFor(name)
                    .lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" },
            // A project with any Kotlin in it is a Kotlin project, and the fast
            // engine says so plainly rather than compiling half of it.
            language = if (layout.kotlinSources().isNotEmpty()) {
                SourceLanguage.KOTLIN
            } else {
                SourceLanguage.JAVA
            },
            engine = BuildEngine.FAST,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    /**
     * The manifest's `package`, or null.
     *
     * Null is ordinary, not an error: AGP 7 moved the application id into the
     * Gradle build and modern manifests have no package attribute at all. The
     * caller derives one from the folder name in that case, which the user can
     * correct later.
     */
    private fun packageOf(manifest: File): String? = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
            .documentElement
            .getAttribute("package")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun displayName(tree: Uri, documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameColumn) ?: FALLBACK_NAME
            }
        }
        return FALLBACK_NAME
    }

    private fun failure(message: String): AppResult<Project> =
        AppResult.Failure(AppError(message))

    companion object {
        const val MEGABYTE = 1024L * 1024L

        /**
         * Enough for a real app, small enough that a mis-picked folder cannot
         * fill a phone before anyone notices.
         */
        const val DEFAULT_MAXIMUM_BYTES = 200L * MEGABYTE

        /** Regenerable or irrelevant, and between them most of a project's bytes. */
        private val SKIPPED = setOf(".git", "build", ".gradle", ".idea", "node_modules")

        private const val MAX_DEPTH = 24
        private const val FALLBACK_NAME = "Imported project"
    }
}
