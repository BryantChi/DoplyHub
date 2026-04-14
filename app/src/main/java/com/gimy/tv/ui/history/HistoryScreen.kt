package com.gimy.tv.ui.history

import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.favorites.EmptyState
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: WatchHistoryRepository) : ViewModel() {
    val history: StateFlow<List<WatchHistoryEntry>> = repo.getRecentHistory(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun clear() { viewModelScope.launch { repo.clearHistory() } }
    fun delete(entry: WatchHistoryEntry) { viewModelScope.launch { repo.deleteEntry(entry.vodId, entry.sourceType) } }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryScreen(onVodClick: (SourceType, Long) -> Unit, onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val history by vm.history.collectAsState()
    var pendingDelete by remember { mutableStateOf<WatchHistoryEntry?>(null) }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            PageHeader("觀看歷史", onBack)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                var f by remember { mutableStateOf(false) }
                Button(onClick = { vm.clear() }, modifier = Modifier.onFocusChanged { f = it.isFocused },
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                    colors = ButtonDefaults.colors(containerColor = CinemaSurface, focusedContainerColor = CinemaRed),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
                ) { Text("清除", color = Color.White, fontSize = 12.sp) }
            }
        }
        if (history.isEmpty()) EmptyState("還沒有觀看記錄")
        else LazyVerticalGrid(GridCells.Adaptive(154.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()
        ) {
            items(history, key = { "${it.sourceType}_${it.vodId}" }) { e ->
                VodCard(Vod(e.vodId, e.sourceType, e.title, e.coverUrl, "", 0, "第${e.episodeNum}集"),
                    onClick = { onVodClick(e.sourceType, e.vodId) },
                    onLongClick = { pendingDelete = e })
            }
        }
    }

    // Delete confirmation dialog — delay interactivity to absorb the long-press key-up
    pendingDelete?.let { entry ->
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(350); armed = true }

        AlertDialog(
            onDismissRequest = { if (armed) pendingDelete = null },
            title = { Text("刪除記錄", color = Color.White) },
            text = { Text("確定要刪除「${entry.title}」的觀看記錄嗎？", color = CinemaTextMuted) },
            confirmButton = {
                Button(
                    onClick = { if (armed) { vm.delete(entry); pendingDelete = null } },
                    colors = ButtonDefaults.colors(containerColor = CinemaRed, focusedContainerColor = CinemaRed),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp))
                ) { Text("刪除", color = Color.White) }
            },
            dismissButton = {
                Button(
                    onClick = { if (armed) pendingDelete = null },
                    colors = ButtonDefaults.colors(containerColor = CinemaSurface, focusedContainerColor = CinemaSurface),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp))
                ) { Text("取消", color = Color.White) }
            },
            containerColor = CinemaSurface,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
