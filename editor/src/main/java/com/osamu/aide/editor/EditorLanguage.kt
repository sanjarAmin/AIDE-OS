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
 * One [Language] per editor per file: nothing here is shared between editors,
 * and the reason is ownership rather than taste -- see [specFor].
 */
class EditorLanguages(private val context: Context) {

    /**
     * The query *text*, not the compiled spec.
     *
     * Caching the spec is the obvious optimisation and is wrong. sora's
     * `TsLanguage.destroy()` closes the spec it was constructed with, and
     * `CodeEditor.setEditorLanguage` destroys the outgoing language -- so a
     * spec shared between two files is closed the moment the second one is
     * opened, and every language built from it afterwards throws
     * "spec is closed". Reading a query out of assets is what is left, and it
     * is a few kilobytes.
     */
    private val queries = mutableMapOf<EditorLanguage, String>()

    @Synchronized
    fun languageFor(file: File): Language {
        // Ordered, not incidental: touching a grammar before the core is loaded
        // throws UnsatisfiedLinkError from inside a static initialiser, which
        // then poisons the class for the life of the process.
        if (!TreeSitterRuntime.isAvailable) return EmptyLanguage()

        val language = EditorLanguage.of(file) ?: return EmptyLanguage()
        return TsLanguage(specFor(language), tab = false) { EditorTheme.applyTo(this) }
    }

    private fun specFor(language: EditorLanguage): TsLanguageSpec = TsLanguageSpec(
        language = language.language(),
        highlightScmSource = queries.getOrPut(language) { query(language, "highlights.scm") },
        // Blocks, brackets and locals are separate queries the grammars do not
        // ship, and the features built on them -- folding markers, bracket
        // matching, scope-aware highlighting -- are not worth blocking syntax
        // colouring on. They cannot be left empty though: the binding rejects a
        // blank query outright, so these are comments, which compile to no
        // patterns and mean the same thing.
        codeBlocksScmSource = NO_PATTERNS,
        bracketsScmSource = NO_PATTERNS,
        localsScmSource = NO_PATTERNS,
    )

    private companion object {
        /** A query with nothing in it. Blank is rejected; a comment is not. */
        const val NO_PATTERNS = "; intentionally empty"
    }

    private fun query(language: EditorLanguage, name: String): String = context.assets
        .open("treesitter/${language.queryDirectory}/$name")
        .bufferedReader()
        .use { it.readText() }
}
