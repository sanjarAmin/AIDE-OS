package com.osamu.aide.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.treesitter.TSQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every vendored highlight query, compiled against the grammar it belongs to.
 *
 * A query is written against a particular revision of its grammar, and ours are
 * upstream queries paired with someone else's prebuilt binaries. When they
 * disagree the query fails to compile, tree-sitter reports a byte offset and
 * nothing else, and the language renders as plain text -- with no error anywhere
 * that a user or a log would show. That silence is the reason this test exists.
 */
@RunWith(AndroidJUnit4::class)
class TreeSitterQueryTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun loadTreeSitter() {
        // The same bootstrap the editor performs. Without it every call below
        // fails with UnsatisfiedLinkError rather than a query error, which
        // would make this test look like a grammar mismatch.
        assertTrue("tree-sitter's native core did not load", TreeSitterRuntime.isAvailable)
    }

    private fun queryText(language: EditorLanguage): String = context.assets
        .open("treesitter/${language.queryDirectory}/highlights.scm")
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun every_highlight_query_compiles_against_its_grammar() {
        for (language in EditorLanguage.entries) {
            val source = queryText(language)
            val query = TSQuery.create(language.language(), source)

            assertTrue(
                "${language.displayName}: ${query.errorType} at ${describe(source, query)}",
                query.isValid,
            )
            query.close()
        }
    }

    @Test
    fun every_grammar_captures_something_the_theme_colours() {
        // A query that compiles but shares no vocabulary with the theme renders
        // as plain text just as convincingly as one that failed.
        for (language in EditorLanguage.entries) {
            val query = TSQuery.create(language.language(), queryText(language))
            val captures = query.captureNames.toSet()
            query.close()

            val coloured = captures intersect EditorTheme.CAPTURES
            assertTrue(
                "${language.displayName} shares no captures with the theme: $captures",
                coloured.isNotEmpty(),
            )
            assertTrue(
                "${language.displayName} does not colour comments: $captures",
                "comment" in coloured,
            )
        }
    }

    @Test
    fun the_theme_has_no_entry_that_no_grammar_produces() {
        // A typo in the theme table -- "punctuation.delimeter" -- colours
        // nothing and reports nothing. This is the only thing that would notice.
        val produced = EditorLanguage.entries.flatMapTo(mutableSetOf()) { language ->
            val query = TSQuery.create(language.language(), queryText(language))
            query.captureNames.toList().also { query.close() }
        }

        assertEquals(
            "these theme entries match no capture any grammar emits",
            emptySet<String>(),
            EditorTheme.CAPTURES - produced,
        )
    }

    /** Turns a byte offset into something a person can act on. */
    private fun describe(source: String, query: TSQuery): String {
        val offset = query.errorOffset.coerceIn(0, source.length)
        val line = source.take(offset).count { it == '\n' } + 1
        val excerpt = source.drop(offset).takeWhile { it != '\n' }.take(60)
        return "line $line: $excerpt"
    }
}
