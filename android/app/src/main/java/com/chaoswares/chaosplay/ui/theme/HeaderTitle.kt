package com.chaoswares.chaosplay.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Screen title in the app header: one line, never wrapping. Starts at the
 * header size and steps down (to [minSize]) when the icons beside it leave
 * too little room, e.g. inside a folder where a back arrow is added.
 */
@Composable
fun HeaderTitle(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 15.sp
) {
    val base = ChaosType.HeaderTitle
    BoxWithConstraints(modifier) {
    // Re-fit from full size whenever the text or the room for it changes
    var size by remember(text, maxWidth) { mutableStateOf(base.fontSize) }
    var fitted by remember(text, maxWidth) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        style = base.copy(fontSize = size, lineHeight = size * 1.3f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        // Hide the frames spent measuring smaller sizes
        modifier = Modifier.drawWithContent { if (fitted) drawContent() },
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size.value > minSize.value) {
                size = (size.value - 1f).sp
            } else {
                fitted = true
            }
        }
    )
    }
}
