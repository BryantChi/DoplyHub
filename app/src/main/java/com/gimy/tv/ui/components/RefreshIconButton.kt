package com.gimy.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaSurface
import com.gimy.tv.ui.theme.CinemaTextMuted
import com.gimy.tv.ui.theme.CinemaTextPrimary
import com.gimy.tv.ui.theme.LocalIsTelevision

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RefreshIconButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isTV = LocalIsTelevision.current

    // Hold the spinning state for a minimum of 600ms so a fast cache-hit
    // refresh is still visually perceivable.
    var displaySpin by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            displaySpin = true
        } else if (displaySpin) {
            delay(600)
            displaySpin = false
        }
    }

    val rotation = if (displaySpin) {
        val transition = rememberInfiniteTransition(label = "refresh-rotation")
        val r by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "refresh-angle"
        )
        r
    } else 0f

    // Always tap-able — ViewModel.refresh() guards reentry. Disabling the TV button
    // also makes it unfocusable, which made the button unreachable on remote.
    val handleClick: () -> Unit = {
        if (enabled && !isRefreshing) onClick()
    }

    if (isTV) {
        var focused by remember { mutableStateOf(false) }
        Button(
            onClick = handleClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(
                containerColor = CinemaSurface.copy(0.6f),
                focusedContainerColor = CinemaRed,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新",
                tint = if (focused) Color.White else CinemaTextMuted,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }
    } else {
        androidx.compose.material3.IconButton(
            onClick = handleClick,
            modifier = modifier
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新",
                tint = CinemaTextPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation)
            )
        }
    }
}
