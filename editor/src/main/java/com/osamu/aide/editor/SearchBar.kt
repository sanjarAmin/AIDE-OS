package com.osamu.aide.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Find and replace, over the editor's own searcher.
 *
 * The matching itself belongs to sora -- it runs off the main thread and
 * highlights matches in the widget, which nothing here could do as well. This
 * is the box that drives it, plus the one piece of state sora does not keep:
 * what the user typed into Replace.
 */
@Composable
fun SearchBar(
    controller: CodeEditorController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = controller.search
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = { controller.searchFor(it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Find") },
                    singleLine = true,
                    isError = search.error != null,
                    supportingText = search.error?.let { { Text(it) } } ?: {
                        Text(
                            when {
                                search.query.isEmpty() -> ""
                                search.matches == 0 -> "No matches"
                                // Nothing is highlighted until Next is pressed,
                                // and "0 of 16" reads as a failure rather than
                                // as sixteen things found.
                                search.current == 0 -> "${search.matches} matches"
                                else -> "${search.current} of ${search.matches}"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                IconButton(
                    onClick = controller::findPrevious,
                    enabled = search.matches > 0,
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match") }
                IconButton(
                    onClick = controller::findNext,
                    enabled = search.matches > 0,
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match") }
                IconButton(
                    onClick = {
                        controller.stopSearch()
                        onDismiss()
                    },
                ) { Icon(Icons.Default.Close, contentDescription = "Close search") }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = search.caseSensitive,
                    onClick = {
                        controller.searchFor(search.query, caseSensitive = !search.caseSensitive)
                    },
                    label = { Text("Aa") },
                )
                FilterChip(
                    selected = search.useRegex,
                    onClick = {
                        controller.searchFor(search.query, useRegex = !search.useRegex)
                    },
                    label = { Text(".*") },
                )
                FilterChip(
                    selected = showReplace,
                    onClick = { showReplace = !showReplace },
                    label = { Text("Replace") },
                )
            }

            if (showReplace) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Replace with") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = { controller.replaceCurrent(replacement) },
                        enabled = search.matches > 0,
                    ) { Text("Replace") }
                    TextButton(
                        onClick = { controller.replaceAll(replacement) },
                        enabled = search.matches > 0,
                    ) { Text("All") }
                }
            }
        }
    }
}
