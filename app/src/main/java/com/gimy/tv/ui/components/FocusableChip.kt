package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaSurface
import com.gimy.tv.ui.theme.CinemaTextMuted
import com.gimy.tv.ui.theme.LocalIsTelevision

/**
 * Compact focusable chip used by SearchScreen, AdultPlusScreen,
 * AdultPlusCategoriesScreen, etc. Renders a TV-Material3 Button on TV (so
 * D-pad focus highlights) and a plain Material3 Button on phone (touch).
 *
 * `primary = true` paints with the brand red regardless of focus, used for
 * the dominant CTA in a row of chips (e.g. "搜尋").
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    var focused by remember { mutableStateOf(false) }
    val chipColor = if (primary) CinemaRed else CinemaSurface
    val textColor = if (focused || primary) Color.White else CinemaTextMuted
    val textWeight = if (primary) FontWeight.Bold else FontWeight.Medium
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { focused = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(
                containerColor = chipColor,
                focusedContainerColor = CinemaRed,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        ) { Text(label, color = textColor, fontSize = 14.sp, fontWeight = textWeight) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = chipColor,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                color = if (primary) Color.White else CinemaTextMuted,
                fontSize = 14.sp,
                fontWeight = textWeight,
            )
        }
    }
}
