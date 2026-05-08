package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gimy.tv.ui.theme.LocalIsTelevision

/**
 * Wraps content with pull-to-refresh on touch devices (phone/tablet).
 * On TV, returns a plain Box because the remote cannot trigger pull gestures —
 * use [RefreshIconButton] for TV-driven refresh instead.
 *
 * Pull threshold is intentionally larger than the Material3 default (80dp → 140dp)
 * to reduce accidental refreshes on long content rows. Caller can also pass
 * `enabled = false` to disable pull-to-refresh entirely (e.g. when the list is
 * scrolled away from the top).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val isTV = LocalIsTelevision.current
    if (isTV) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = state,
                enabled = enabled,
                threshold = 140.dp,
                onRefresh = onRefresh,
            ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
        )
    }
}
