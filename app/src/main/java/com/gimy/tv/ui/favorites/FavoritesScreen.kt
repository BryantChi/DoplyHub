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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.FavoriteRepository
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(fav: FavoriteRepository) : ViewModel() {
    val favorites: StateFlow<List<Vod>> = fav.getFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(onVodClick: (SourceType, Long) -> Unit, onBack: () -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val favs by vm.favorites.collectAsState()
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        PageHeader("我的收藏", onBack)
        if (favs.isEmpty()) EmptyState("還沒有收藏的內容")
        else LazyVerticalGrid(GridCells.Adaptive(154.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()
        ) { items(favs, key = { "${it.sourceType}_${it.id}" }) { vod -> VodCard(vod, onClick = { onVodClick(vod.sourceType, vod.id) }) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        var f by remember { mutableStateOf(false) }
        Button(onClick = onBack, modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(containerColor = CinemaSurface, focusedContainerColor = CinemaRed),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
        ) { Text("返回", color = Color.White, fontSize = 13.sp) }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(CinemaRed))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary)
    }
}

@Composable
fun EmptyState(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = CinemaTextMuted, fontSize = 14.sp)
    }
}
