package com.gimy.tv.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.favorites.EmptyState
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: WatchHistoryRepository,
) : ViewModel() {
    /** Entries that failed to open; cleared on demand. */
    val staleCount: StateFlow<Int> = repo.staleCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun clearStale() { viewModelScope.launch { runCatching { repo.clearStale() } } }

    val history: StateFlow<List<WatchHistoryEntry>> = repo.getRecentHistory(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun clear() { viewModelScope.launch { repo.clearHistory() } }
    fun delete(entry: WatchHistoryEntry) { viewModelScope.launch { repo.deleteEntry(entry.vodId, entry.sourceType) } }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryScreen(onVodClick: (SourceType, Long) -> Unit, onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val history by vm.history.collectAsStateWithLifecycle()
    val staleCount by vm.staleCount.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<WatchHistoryEntry?>(null) }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        // 按鈕要放進 PageHeader 的 actions 槽。PageHeader 自己就是一個 fillMaxWidth 的 Row，
        // 外面再包一層 Row 的話它會把寬度吃光，後面的按鈕量到 0 寬、等於看不見。
        PageHeader(stringResource(R.string.history_title), onBack) {
            // Sits before the blanket "clear": removing only the entries that no longer open
            // is far less destructive than wiping the whole history.
            if (staleCount > 0) {
                DoplyButton(
                    onClick = { vm.clearStale() },
                    containerColor = CinemaSurface,
                    focusBorder = false,
                ) { Text(stringResource(R.string.common_clear_stale, staleCount), color = Color.White, fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
            }
            if (history.isNotEmpty()) {
                DoplyButton(
                    onClick = { vm.clear() },
                    shape = RoundedCornerShape(6.dp),
                    containerColor = CinemaSurface,
                    contentColor = Color.White,
                    focusBorder = false,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                ) { Text(stringResource(R.string.common_clear), color = Color.White, fontSize = 12.sp) }
            }
        }
        if (history.isEmpty()) EmptyState(stringResource(R.string.history_empty))
        else LazyVerticalGrid(GridCells.Adaptive(dims.cardWidth), contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing), verticalArrangement = Arrangement.spacedBy(dims.cardSpacing), modifier = Modifier.fillMaxSize()
        ) {
            items(history, key = { "${it.sourceType}_${it.vodId}" }) { e ->
                VodCard(Vod(e.vodId, e.sourceType, e.title, e.coverUrl, "", 0, stringResource(R.string.common_episode_n, e.episodeNum)),
                    onClick = { onVodClick(e.sourceType, e.vodId) },
                    onLongClick = { pendingDelete = e })
            }
        }
    }

    // Delete confirmation dialog — delay interactivity to absorb the long-press key-up
    pendingDelete?.let { entry ->
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(350); armed = true }

        if (isTV) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (armed) pendingDelete = null },
                title = { Text(stringResource(R.string.common_delete_history), color = Color.White) },
                text = { Text(stringResource(R.string.common_delete_history_confirm, entry.title), color = CinemaTextMuted) },
                confirmButton = {
                    DoplyButton(
                        onClick = { if (armed) { vm.delete(entry); pendingDelete = null } },
                        containerColor = CinemaRed,
                        shape = RoundedCornerShape(6.dp)
                    ) { Text(stringResource(R.string.common_delete), color = Color.White) }
                },
                dismissButton = {
                    DoplyButton(
                        onClick = { if (armed) pendingDelete = null },
                        containerColor = CinemaSurface,
                        shape = RoundedCornerShape(6.dp)
                    ) { Text(stringResource(R.string.common_cancel), color = Color.White) }
                },
                containerColor = CinemaSurface,
                shape = RoundedCornerShape(12.dp)
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.common_delete_history), color = Color.White) },
                text = { Text(stringResource(R.string.common_delete_history_confirm, entry.title), color = CinemaTextMuted) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.delete(entry); pendingDelete = null }) {
                        Text(stringResource(R.string.common_delete), color = CinemaRed)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.common_cancel), color = CinemaTextPrimary)
                    }
                },
                containerColor = CinemaSurface,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}
