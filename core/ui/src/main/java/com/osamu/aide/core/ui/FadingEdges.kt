package com.osamu.aide.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Softens the ends of a horizontally scrolling row that has more to show.
 *
 * **A row that scrolls looks identical to a row that is cut off.** At 360 dp
 * the language filter ended in a chip reading "J", the tool dock showed four of
 * its six tabs, and the logcat levels stopped after "Warn" -- in each case a
 * glyph sliced down the middle at the screen edge, which reads as a rendering
 * fault rather than as an invitation to swipe. The template picker had the same
 * problem and solved it with a sentence ("scroll for the rest"), which is a
 * caption apologising for a missing affordance.
 *
 * **Faded with `DstIn`, not painted with the background colour.** These rows sit
 * on three different surfaces, and a gradient into an assumed colour is wrong
 * on two of them and wrong again in the other theme. Rendering the row to a
 * layer and erasing its alpha at the edges fades to whatever is actually
 * behind, so it is correct everywhere without being told anything.
 *
 * Each edge appears only when there is content past it, so a row that fits
 * wears no decoration at all.
 */
fun Modifier.fadingEdges(state: ScrollState, width: Dp = 24.dp): Modifier = this
    // Required for DstIn to erase rather than blend against the window.
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx()
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fade,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - fade,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
