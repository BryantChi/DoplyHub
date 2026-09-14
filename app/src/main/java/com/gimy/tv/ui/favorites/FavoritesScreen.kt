package com.gimy.tv.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.FavoriteRepository
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*
import kotlinx.coroutines.launch
import com.gimy.tv.ui.components.DoplyButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val fav: FavoriteRepository,
) : ViewModel() {
    val favorites: StateFlow<List<Vod>> = fav.getFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Entries that failed to open. Surfaced so the user can clear them deliberately,
     *  rather than relying only on the conservative automatic retirement. */
    val staleCount: StateFlow<Int> = fav.staleCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun clearStale() { viewModelScope.launch { runCatching { fav.clearStale() } } }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(onVodClick: (SourceType, Long) -> Unit, onBack: () -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val dims = LocalDimensions.current
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val staleCount by vm.staleCount.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        PageHeader(stringResource(R.string.favorites_title), onBack) {
            if (staleCount > 0) {
                DoplyButton(onClick = { vm.clearStale() }, containerColor = CinemaSurface) {
                    Text(stringResource(R.string.common_clear_stale, staleCount), color = CinemaTextPrimary, fontSize = 12.sp)
                }
            }
        }
        if (favs.isEmpty()) EmptyState(stringResource(R.string.favorites_empty))
        else LazyVerticalGrid(GridCells.Adaptive(dims.cardWidth), contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing), verticalArrangement = Arrangement.spacedBy(dims.cardSpacing), modifier = Modifier.fillMaxSize()
        ) { items(favs, key = { "${it.sourceType}_${it.id}" }) { vod -> VodCard(vod, onClick = { onVodClick(vod.sourceType, vod.id) }) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
/**
 * Shared page header. [actions] is an optional trailing slot so screens can put a refresh
 * button where the general-browse screens have one — without it the 18+ screens had no
 * focusable refresh entry at all, only pull-to-refresh.
 */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val dims = LocalDimensions.current
    Row(Modifier.fillMaxWidth().padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding), verticalAlignment = Alignment.CenterVertically) {
        var f by remember { mutableStateOf(false) }
        DoplyButton(
            onClick = onBack,
            shape = RoundedCornerShape(6.dp),
            containerColor = CinemaSurface,
            contentColor = Color.White,
            focusBorder = false,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        ) { Text(stringResource(R.string.common_back), color = Color.White, fontSize = 13.sp) }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(CinemaRed))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun EmptyState(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = CinemaTextMuted, fontSize = 14.sp)
    }
}
