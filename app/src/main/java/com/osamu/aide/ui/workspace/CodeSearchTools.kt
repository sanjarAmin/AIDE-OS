package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.AideTool
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

/**
 * Symbol definition and declaration search tools for the assistant.
 *
 * Fast code intelligence across multi-language source trees (Kotlin, Java,
 * C/C++, JS/TS, XML). Finds where classes, interfaces, functions, methods, and
 * variables are declared without needing exact line/column offsets.
 */
fun codeSearchTools(
    project: () -> Project?,
): List<AideTool> = listOf(
    AideTool(
        name = "search_definitions",
        description =
            "Search for declarations and definitions of classes, interfaces, functions, " +
                "methods, and variables across project source files (Kotlin, Java, C/C++, JS/TS). " +
                "Returns file path, line number, and matching code snippet. Use this when you want " +
                "to find where a symbol is declared rather than searching raw text with grep.",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf(
            "query" to AideTool.Parameter(
                "string",
                "Name of the class, function, method, or symbol to locate (e.g. 'MainActivity', 'build', 'onCreate').",
            ),
            "path" to AideTool.Parameter(
                "string",
                "Subdirectory relative to the project root to restrict the search. Omit for the whole project.",
            ),
        ),
        required = listOf("query"),
        handler = { input ->
            val query = input["query"]?.trim()
            if (query.isNullOrEmpty()) {
                return@AideTool ProjectFiles.Outcome.Refused("search_definitions needs a query.")
            }
            val target = project()
                ?: return@AideTool ProjectFiles.Outcome.Refused(
                    "No project is open, so there is nowhere to search.",
                )

            val subPath = input["path"]?.trim().orEmpty()
            val searchDir = if (subPath.isEmpty()) target.rootDir else File(target.rootDir, subPath)
            if (!searchDir.exists()) {
                return@AideTool ProjectFiles.Outcome.Refused("Directory '$subPath' does not exist in this project.")
            }

            searchDefinitions(target.rootDir, searchDir, query)
        },
    ),
)

private val SOURCE_EXTENSIONS = setOf(
    "kt", "kts", "java", "c", "cpp", "cc", "cxx", "h", "hpp", "js", "jsx", "ts", "tsx", "xml",
)

private const val MAX_MATCHES = 30

internal suspend fun searchDefinitions(
    projectRoot: File,
    searchDir: File,
    query: String,
): ProjectFiles.Outcome = withContext(Dispatchers.IO) {
    val escaped = Pattern.quote(query)
    // Regex patterns targeting symbol declarations:
    // 1. Types: class / interface / object / enum / struct / typealias / record
    val typePattern = Pattern.compile("""\b(class|interface|object|enum\s+class|enum|struct|typealias|record|@interface)\s+$escaped\b""")
    // 2. Functions / methods: fun name( or returnType name(
    val funPattern = Pattern.compile("""\b(fun|def|function)\s+$escaped\s*[\(<]""")
    val javaFunPattern = Pattern.compile("""(public|protected|private|static|final|synchronized|abstract|default|\s)\s+[\w<>\[\],\s]+\s+$escaped\s*\(""")
    // 3. Variables / fields: val / var / const / static type name
    val valPattern = Pattern.compile("""\b(val|var|const|let)\s+$escaped\b""")
    // 4. Android XML components: android:name=".MainActivity" or name="query"
    val xmlPattern = Pattern.compile("""(android:name|name)=["'].*?$escaped["']""")

    val matches = mutableListOf<String>()

    searchDir.walkTopDown()
        .onEnter { dir ->
            val name = dir.name
            name != "build" && name != ".git" && name != ".gradle" && name != "node_modules" && !name.startsWith(".")
        }
        .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
        .forEach { file ->
            if (matches.size >= MAX_MATCHES) return@forEach
            val relPath = file.relativeTo(projectRoot).path

            try {
                file.useLines { lines ->
                    var lineNum = 1
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                            val isMatch = typePattern.matcher(line).find() ||
                                funPattern.matcher(line).find() ||
                                javaFunPattern.matcher(line).find() ||
                                valPattern.matcher(line).find() ||
                                (file.extension == "xml" && xmlPattern.matcher(line).find())

                            if (isMatch) {
                                matches.add("$relPath:$lineNum: $trimmed")
                                if (matches.size >= MAX_MATCHES) break
                            }
                        }
                        lineNum++
                    }
                }
            } catch (_: Exception) {
                // Ignore unreadable files
            }
        }

    if (matches.isEmpty()) {
        ProjectFiles.Outcome.Ok("No definitions found for \"$query\" in source files.")
    } else {
        val result = buildString {
            append("Found ").append(matches.size)
            if (matches.size >= MAX_MATCHES) append("+")
            append(" definition(s) for \"").append(query).append("\":\n")
            matches.forEach { append(it).append("\n") }
        }.trim()
        ProjectFiles.Outcome.Ok(result)
    }
}
