package com.osamu.aide.ai.core

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool

/**
 * Whether running a tool can change the user's project.
 *
 * `docs/PLAN.md` requires every mutating tool to sit behind an explicit
 * confirmation. Modelling that as data rather than leaving it to the UI is the
 * point: a screen that forgets to prompt is a bug you find in review, while a
 * dispatcher that refuses to run without approval is one you cannot write.
 */
enum class ToolRisk { READ_ONLY, MUTATING }

/** One capability offered to the model, and the code behind it. */
class AideTool(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    private val parameters: Map<String, Parameter>,
    private val required: List<String>,
    private val handler: suspend (Map<String, String>) -> ProjectFiles.Outcome,
) {
    data class Parameter(val type: String, val description: String)

    /** The definition sent to the API. */
    fun definition(): Tool = Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder()
                        .apply {
                            parameters.forEach { (key, parameter) ->
                                putAdditionalProperty(
                                    key,
                                    JsonValue.from(
                                        mapOf(
                                            "type" to parameter.type,
                                            "description" to parameter.description,
                                        ),
                                    ),
                                )
                            }
                        }
                        .build(),
                )
                .required(required)
                .build(),
        )
        .build()

    internal suspend fun run(input: Map<String, String>): ProjectFiles.Outcome = handler(input)
}

/**
 * The tools the assistant can use against one project.
 *
 * Four of the plan's six are defined here. `run_build` and `read_build_errors`
 * need the build engine, so they arrive through [extra] from the module that
 * owns it -- defining them here would drag `:engine:fast` into a module whose
 * job is talking to an API.
 *
 * Descriptions are written for the model rather than for a reader of this file.
 * They say when to reach for a tool and what it costs, because that is what the
 * model is choosing between; "reads a file" tells it nothing it could not
 * guess from the name.
 */
class ProjectToolset(
    private val files: ProjectFiles,
    /**
     * Tools contributed by other layers, appended in the order given.
     *
     * Appended rather than merged by name, because tool order is part of the
     * cached prefix -- see [definitions]. A caller that varies this list
     * between turns of one conversation pays for the whole cache and gets a
     * perfectly good answer, so the list must be built once per session.
     */
    extra: List<AideTool> = emptyList(),
) {

    private val tools: List<AideTool> = listOf(
        AideTool(
            name = "list_files",
            description =
                "List the project's source files as paths relative to its root. " +
                    "Start here when you do not yet know the layout. Build output " +
                    "and version control are excluded.",
            risk = ToolRisk.READ_ONLY,
            parameters = mapOf(
                "path" to AideTool.Parameter(
                    "string",
                    "Directory to list, relative to the project root. Omit for the whole project.",
                ),
            ),
            required = emptyList(),
            handler = { files.list(it["path"].orEmpty()) },
        ),
        AideTool(
            name = "read_file",
            description =
                "Read one file in full. Prefer grep when you are looking for " +
                    "something and do not know which file holds it -- reading whole " +
                    "files to search them wastes the context you will need later.",
            risk = ToolRisk.READ_ONLY,
            parameters = mapOf(
                "path" to AideTool.Parameter(
                    "string",
                    "File to read, relative to the project root.",
                ),
            ),
            required = listOf("path"),
            handler = { input ->
                input["path"]?.let(files::read)
                    ?: ProjectFiles.Outcome.Refused("read_file needs a path.")
            },
        ),
        AideTool(
            name = "grep",
            description =
                "Find a literal string across the project and get back file, line " +
                    "number and the matching line. This is a plain substring search, " +
                    "not a regular expression.",
            risk = ToolRisk.READ_ONLY,
            parameters = mapOf(
                "query" to AideTool.Parameter("string", "Literal text to search for."),
                "path" to AideTool.Parameter(
                    "string",
                    "Directory to search under. Omit to search the whole project.",
                ),
            ),
            required = listOf("query"),
            handler = { input ->
                input["query"]?.let { files.grep(it, input["path"].orEmpty()) }
                    ?: ProjectFiles.Outcome.Refused("grep needs a query.")
            },
        ),
        AideTool(
            name = "edit_file",
            description =
                "Replace a file's entire contents. Read it first unless you are " +
                    "creating it -- this overwrites rather than patches, so anything " +
                    "you leave out is deleted. Missing directories are created. " +
                    "The user is asked to confirm before this runs.",
            risk = ToolRisk.MUTATING,
            parameters = mapOf(
                "path" to AideTool.Parameter(
                    "string",
                    "File to write, relative to the project root.",
                ),
                "content" to AideTool.Parameter("string", "The file's complete new contents."),
            ),
            required = listOf("path", "content"),
            handler = { input ->
                val path = input["path"]
                val content = input["content"]
                if (path == null || content == null) {
                    ProjectFiles.Outcome.Refused("edit_file needs both a path and content.")
                } else {
                    files.write(path, content)
                }
            },
        ),
    ) + extra

    /** Every tool, in a fixed order — see [definitions]. */
    fun all(): List<AideTool> = tools

    fun find(name: String): AideTool? = tools.firstOrNull { it.name == name }

    /**
     * The tool definitions, for the request.
     *
     * Order is fixed and must stay that way. Tools are rendered before the
     * system prompt and the messages, so they sit at the very front of the
     * cached prefix -- reordering them invalidates the cache for every
     * conversation, and the only symptom is a larger bill.
     */
    fun definitions(): List<Tool> = tools.map { it.definition() }

    /**
     * Runs a tool the model asked for.
     *
     * [approved] is the confirmation gate, and it is a parameter rather than a
     * convention so that forgetting it fails closed. A mutating tool with no
     * approval is refused here, whatever the UI did or did not do -- the model
     * can ask to overwrite a file on any turn, including one the user is not
     * watching.
     */
    suspend fun execute(
        name: String,
        input: Map<String, String>,
        approved: Boolean = false,
    ): ProjectFiles.Outcome {
        val tool = find(name)
            ?: return ProjectFiles.Outcome.Refused("There is no tool called \"$name\".")

        if (tool.risk == ToolRisk.MUTATING && !approved) {
            return ProjectFiles.Outcome.Refused(
                "$name changes the project and was not confirmed by the user.",
            )
        }
        return tool.run(input)
    }
}
