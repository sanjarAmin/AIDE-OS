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
 * A syntax-highlighted code block with language indicator and quick actions.
 *
 * Provides one-tap "Copy" with temporary confirmation, and "Insert at Cursor"
 * when an editor callback is provided.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    onInsertCode: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val displayLang = language?.trim()?.uppercase()?.ifBlank { "CODE" } ?: "CODE"
    val highlightedText = remember(code, language) {
        highlightSyntax(code, language)
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
                Text(
                    text = displayLang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onInsertCode != null) {
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

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.size(28.dp),
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

            // Code Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = highlightedText,
                    style = CodeTextStyle.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
    }
}

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
