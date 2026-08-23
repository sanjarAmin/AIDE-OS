package com.osamu.aide.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One key on the symbol row.
 *
 * [insert] is separate from [label] so a key can read as one thing and type
 * another -- `TAB` types four spaces, `→` types an arrow.
 */
data class EditorSymbol(val label: String, val insert: String = label)

/**
 * The row of characters a soft keyboard buries.
 *
 * This is not a nicety on a phone. Every one of these is one or two modifier
 * layers deep on the stock keyboard, and writing code means reaching for them
 * constantly -- a semicolon costs a layer switch, a brace costs two. The row is
 * the difference between typing code on a phone and merely being able to.
 */
@Composable
fun SymbolRow(
    controller: CodeEditorController,
    modifier: Modifier = Modifier,
    symbols: List<EditorSymbol> = EditorSymbols.DEFAULT,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(symbols, key = { it.label }) { symbol ->
                Surface(
                    onClick = { controller.insert(symbol.insert) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Box(
                        modifier = Modifier.size(width = 42.dp, height = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = symbol.label,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

object EditorSymbols {

    /**
     * Ordered by how often code needs them, not by ASCII.
     *
     * TAB types spaces rather than a tab character: the project template and
     * every generated file are space-indented, and a row that quietly mixes the
     * two produces diffs that look like whitespace vandalism.
     */
    val DEFAULT: List<EditorSymbol> = listOf(
        EditorSymbol("TAB", "    "),
        EditorSymbol("{"),
        EditorSymbol("}"),
        EditorSymbol("("),
        EditorSymbol(")"),
        EditorSymbol("["),
        EditorSymbol("]"),
        EditorSymbol(";"),
        EditorSymbol("."),
        EditorSymbol(","),
        EditorSymbol("="),
        EditorSymbol("\""),
        EditorSymbol("'"),
        EditorSymbol(":"),
        EditorSymbol("<"),
        EditorSymbol(">"),
        EditorSymbol("+"),
        EditorSymbol("-"),
        EditorSymbol("*"),
        EditorSymbol("/"),
        EditorSymbol("_"),
        EditorSymbol("&"),
        EditorSymbol("|"),
        EditorSymbol("!"),
        EditorSymbol("?"),
        EditorSymbol("@"),
        EditorSymbol("$"),
        EditorSymbol("#"),
        EditorSymbol("%"),
        EditorSymbol("^"),
        EditorSymbol("~"),
        EditorSymbol("\\"),
        EditorSymbol("`"),
    )
}
