package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.core.ui.theme.CodeTextStyle
import kotlinx.coroutines.delay
import java.util.regex.Pattern

/**
 * A code block: the thing this panel exists to produce.
 *
 * **Treated as a first-class citizen, not styled prose.** In a general-purpose
 * chat a code block is a quotation; in an IDE it is the deliverable, and the
 * two actions that matter -- put this in my file, put this on my clipboard --
 * are always in the same place rather than hidden behind a long-press.
 *
 * **It scrolls sideways and does not wrap by default.** Wrapping code changes
 * what it means: an 80-column line folded at 40 reads as two statements, and
 * indentation stops lining up. The toggle exists because on a phone a long
 * string literal is genuinely easier to read wrapped, but the default respects
 * the line breaks the author chose.
 *
 * **Long blocks collapse.** A model asked to rewrite a file returns the file,
 * and 300 lines of it between two paragraphs makes the answer unreadable and
 * unscrollable -- the transcript becomes one message. Anything over
 * [COLLAPSE_LINES] shows its head with a tap to open.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    onInsertCode: ((String) -> Unit)? = null,
    /**
     * False while the fence is still streaming.
     *
     * The actions are withheld until the block closes: copying or inserting
     * half a function produces something that does not compile, and the person
     * has no way to know it was incomplete once it is in their file.
     */
    complete: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var wrapped by remember { mutableStateOf(false) }
    var expanded by remember(code) { mutableStateOf(false) }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val collapsible = lineCount > COLLAPSE_LINES
    val shown = remember(code, expanded, collapsible) {
        if (collapsible && !expanded) code.lineSequence().take(COLLAPSE_LINES).joinToString("\n") else code
    }
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // Sentence case, not caps. A tracked-out uppercase label above a block is
    // the commonest generated-UI tell, and the language is a fact about the
    // code rather than a heading that needs shouting.
    val displayLang = language?.trim()?.lowercase()?.ifBlank { null } ?: "code"
    val highlightedText = remember(shown, language) {
        highlightSyntax(shown, language)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    // The label takes the room the actions leave, so a long
                    // language name cannot squeeze the buttons to nothing --
                    // the defect CLAUDE.md counts nine of.
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = displayLang,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (collapsible) {
                        Text(
                            text = "$lineCount lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = { wrapped = !wrapped },
                        modifier = Modifier.size(28.dp).testTag(CODE_WRAP_TAG),
                    ) {
                        Icon(
                            Icons.Default.WrapText,
                            contentDescription = if (wrapped) "Stop wrapping lines" else "Wrap long lines",
                            tint = if (wrapped) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (onInsertCode != null && complete) {
                        IconButton(
                            onClick = { onInsertCode(code) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Input,
                                contentDescription = "Insert at cursor",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    if (complete) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.size(28.dp).testTag(CODE_COPY_TAG),
                    ) {
                        if (copied) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Copied",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy code",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    }
                }
            }

            val codeStyle = CodeTextStyle.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrapped) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(10.dp),
            ) {
                Text(
                    text = highlightedText,
                    style = codeStyle,
                    softWrap = wrapped,
                )
            }

            if (collapsible) {
                // A full-width target rather than a small link: this is the
                // control most likely to be tapped with a thumb.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().testTag(CODE_EXPAND_TAG),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) {
                                "Show less"
                            } else {
                                "Show all $lineCount lines"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Long enough to read the shape of a function, short enough to scroll past. */
private const val COLLAPSE_LINES = 14

const val CODE_COPY_TAG = "code-copy"
const val CODE_WRAP_TAG = "code-wrap"
const val CODE_EXPAND_TAG = "code-expand"

/**
 * Fast, lightweight syntax highlighter for code snippets in the chat.
 */
private fun highlightSyntax(code: String, language: String?): AnnotatedString {
    val lang = language?.lowercase()?.trim().orEmpty()
    val isKotlinOrJava = lang in setOf("kotlin", "kt", "kts", "java")
    val isBash = lang in setOf("bash", "sh", "shell")

    val builder = AnnotatedString.Builder(code)

    val keywordColor = Color(0xFFE57B42) // Warm orange/coral
    val stringColor = Color(0xFF6AAB73)  // Green
    val commentColor = Color(0xFF7A7E85) // Gray
    val numberColor = Color(0xFF2AACB8)  // Cyan
    val annotationColor = Color(0xFFB3AE60) // Gold
    val typeColor = Color(0xFF56A8F5)    // Light blue

    // Comments
    val commentRegex = Pattern.compile(if (isBash) "(#.*$)" else "(//.*$|/\\*[\\s\\S]*?\\*/)", Pattern.MULTILINE)
    val commentMatcher = commentRegex.matcher(code)
    while (commentMatcher.find()) {
        builder.addStyle(
            SpanStyle(color = commentColor, fontStyle = FontStyle.Italic),
            commentMatcher.start(),
            commentMatcher.end(),
        )
    }

    // Strings
    val stringRegex = Pattern.compile("(\"[^\"]*\"|'[^']*'|`[^`]*`)")
    val stringMatcher = stringRegex.matcher(code)
    while (stringMatcher.find()) {
        builder.addStyle(
            SpanStyle(color = stringColor),
            stringMatcher.start(),
            stringMatcher.end(),
        )
    }

    // Numbers
    val numberRegex = Pattern.compile("\\b(\\d+(\\.\\d+)?([fFL]|px|dp)?)\\b")
    val numberMatcher = numberRegex.matcher(code)
    while (numberMatcher.find()) {
        builder.addStyle(
            SpanStyle(color = numberColor),
            numberMatcher.start(),
            numberMatcher.end(),
        )
    }

    // Annotations / Decorators
    val annotationRegex = Pattern.compile("(@[a-zA-Z0-9_]+)")
    val annotationMatcher = annotationRegex.matcher(code)
    while (annotationMatcher.find()) {
        builder.addStyle(
            SpanStyle(color = annotationColor),
            annotationMatcher.start(),
            annotationMatcher.end(),
        )
    }

    if (isKotlinOrJava || lang in setOf("gradle", "c", "cpp", "js", "ts", "python", "py")) {
        val keywords = "\\b(val|var|fun|class|interface|object|enum|sealed|data|override|private|protected|" +
            "public|internal|import|package|return|if|else|when|while|for|in|is|as|try|catch|finally|throw|" +
            "this|super|null|true|false|suspend|companion|const|new|void|int|long|boolean|def|let|const)\\b"
        val keywordMatcher = Pattern.compile(keywords).matcher(code)
        while (keywordMatcher.find()) {
            builder.addStyle(
                SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold),
                keywordMatcher.start(),
                keywordMatcher.end(),
            )
        }

        // Common Types / PascalCase Words
        val typeMatcher = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]+)\\b").matcher(code)
        while (typeMatcher.find()) {
            builder.addStyle(
                SpanStyle(color = typeColor),
                typeMatcher.start(),
                typeMatcher.end(),
            )
        }
    }

    return builder.toAnnotatedString()
}
