package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gimy.tv.ui.theme.LocalIsTelevision

/**
 * Wraps content with pull-to-refresh on touch devices (phone/tablet).
 * On TV, returns a plain Box because the remote cannot trigger pull gestures —
 * use [RefreshIconButton] for TV-driven refresh instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val isTV = LocalIsTelevision.current
    if (isTV) {
        Box(modifier = modifier.fillMaxSize(), content = content)
    } else {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
            content = content,
        )
    }
}
