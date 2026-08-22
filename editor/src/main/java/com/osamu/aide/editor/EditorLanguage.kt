package com.osamu.aide.editor

import android.content.Context
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import java.io.File

/**
 * The languages the editor can highlight, and which files each one claims.
 *
 * Highlighting is tree-sitter rather than TextMate: it parses to a real syntax
 * tree and reparses only what changed, which is what keeps a 5,000-line file
 * scrolling smoothly while it is being typed into. The cost is that grammars
 * are native code -- see `assets/treesitter/README.md` for where they and their
 * queries come from.
 */
enum class EditorLanguage(
    val displayName: String,
    private val extensions: Set<String>,
    val queryDirectory: String,
    private val grammar: () -> TSLanguage,
) {
    JAVA("Java", setOf("java"), "java", { TSLanguageJava.getInstance() }),
    KOTLIN("Kotlin", setOf("kt", "kts"), "kotlin", { TSLanguageKotlin.getInstance() }),
    XML("XML", setOf("xml"), "xml", { TSLanguageXml.getInstance() }),
    JSON("JSON", setOf("json"), "json", { TSLanguageJson.getInstance() });

    fun language(): TSLanguage = grammar()

    companion object {
        /** Null for anything unrecognised, which the editor shows as plain text. */
        fun of(file: File): EditorLanguage? {
            val extension = file.extension.lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/**
 * Builds the sora [Language] for a file.
 *
 * Grammars and their compiled queries are expensive to construct and are held
 * for the life of the process, keyed by language: opening a second Java file
 * should not reparse the grammar, and on a phone that difference is visible.
 */
class EditorLanguages(private val context: Context) {

    private val specs = mutableMapOf<EditorLanguage, TsLanguageSpec>()

    @Synchronized
    fun languageFor(file: File): Language {
        // Ordered, not incidental: touching a grammar before the core is loaded
        // throws UnsatisfiedLinkError from inside a static initialiser, which
        // then poisons the class for the life of the process.
        if (!TreeSitterRuntime.isAvailable) return EmptyLanguage()

        val language = EditorLanguage.of(file) ?: return EmptyLanguage()
        val spec = specs.getOrPut(language) { specFor(language) }
        return TsLanguage(spec, tab = false) { EditorTheme.applyTo(this) }
    }

    private fun specFor(language: EditorLanguage): TsLanguageSpec = TsLanguageSpec(
        language = language.language(),
        highlightScmSource = query(language, "highlights.scm"),
        // Blocks, brackets and locals are separate queries the grammars do not
        // ship. Empty is legal and costs only the features built on them --
        // code folding markers and scope-aware highlighting -- neither of which
        // is worth blocking syntax colouring on.
        codeBlocksScmSource = "",
        bracketsScmSource = "",
        localsScmSource = "",
    )

    private fun query(language: EditorLanguage, name: String): String = context.assets
        .open("treesitter/${language.queryDirectory}/$name")
        .bufferedReader()
        .use { it.readText() }
}
