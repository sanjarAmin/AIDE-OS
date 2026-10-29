package com.osamu.aide.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.core.ui.theme.AideBlue
import com.osamu.aide.core.ui.theme.AideOnAccent
import com.osamu.aide.core.ui.theme.AidePurple

/**
 * The app's mark: a prompt, on the two accents the app is drawn in.
 *
 * The launcher icon is still the template's Android head, so there was nothing
 * to reuse; this is drawn rather than shipped as a vector so it scales with
 * [size] and needs no resource in whichever module shows it.
 *
 * The same in both themes on purpose. Both accents are light enough that the
 * near-black glyph reads on them against a dark page and a light one, and a
 * mark that changes colour with the theme stops being a mark.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                Brush.linearGradient(listOf(AideBlue, AidePurple)),
                RoundedCornerShape(size * 0.3f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ">_",
            color = AideOnAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            // In proportion to the box, so a 56 dp mark in About is the same
            // drawing as the 34 dp one in the toolbar.
            fontSize = (size.value * 0.4f).sp,
        )
    }
}
