package com.gimy.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gimy.tv.ui.theme.CinemaRed
import kotlinx.coroutines.delay

/**
 * Thin top-of-screen indeterminate progress bar shown during a manual refresh.
 * Stays visible for a minimum of 600ms so a fast cache-hit refresh is still perceivable.
 */
@Composable
fun RefreshLoadingBar(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            visible = true
        } else if (visible) {
            delay(600)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = CinemaRed,
            trackColor = Color.Transparent,
        )
    }
}
