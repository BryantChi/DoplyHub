package com.gimy.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.GimyLoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }

    BackHandler {
        if (uiState.hasSearched) viewModel.clearResults() else onBack()
    }

    // Auto-focus the input on first load (delay to ensure layout is ready on TV)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try { inputFocusRequester.requestFocus() } catch (_: Exception) { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
            .padding(horizontal = 48.dp, vertical = 20.dp)
    ) {
        // ── Search bar ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusableChip("返回") {
                if (uiState.hasSearched) viewModel.clearResults() else onBack()
            }
            Spacer(Modifier.width(12.dp))

            // Use Material3 OutlinedTextField — has proper keyboard integration on Android TV
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = { androidx.compose.material3.Text("輸入關鍵字搜尋…", color = CinemaTextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.search()
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CinemaTextPrimary,
                    unfocusedTextColor = CinemaTextPrimary,
                    focusedBorderColor = CinemaRed,
                    unfocusedBorderColor = CinemaBorder,
                    cursorColor = CinemaRed,
                    focusedContainerColor = CinemaSurface,
                    unfocusedContainerColor = CinemaCard
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .focusRequester(inputFocusRequester)
            )
            if (uiState.query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                FocusableChip("✕") {
                    viewModel.onQueryChange("")
                    inputFocusRequester.requestFocus()
                }
            }
            Spacer(Modifier.width(8.dp))

            FocusableChip("搜尋", primary = true) {
                viewModel.search()
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Content area ──
        when {
            uiState.isSearching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GimyLoadingIndicator(48.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("搜尋「${uiState.query}」中…", color = CinemaTextMuted, fontSize = 14.sp)
                    }
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("搜尋失敗", color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(uiState.error ?: "", color = CinemaTextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        FocusableChip("重試") { viewModel.search() }
                    }
                }
            }
            uiState.results.isNotEmpty() -> {
                val gridState = rememberLazyGridState()

                // Auto load more when scrolling near bottom
                LaunchedEffect(gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
                    val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
                    val total = gridState.layoutInfo.totalItemsCount
                    if (total > 0 && lastVisible >= total - 4) {
                        viewModel.loadMore()
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("找到 ${uiState.results.size} 個結果", color = CinemaTextMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(166.dp),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(uiState.results, key = { "${it.sourceType}_${it.id}" }) { vod ->
                                VodCard(vod, onClick = { onVodClick(vod.sourceType, vod.id) })
                            }
                        }
                        if (!uiState.hasMore && !uiState.isLoadingMore) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("已顯示全部結果", color = CinemaTextMuted.copy(0.6f), fontSize = 13.sp)
                            }
                        }
                    }
                    // Loading overlay centered lower
                    if (uiState.isLoadingMore) {
                        Box(
                            Modifier.fillMaxSize().padding(top = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GimyLoadingIndicator(48.dp)
                        }
                    }
                }
            }
            uiState.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("找不到「${uiState.query}」", color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("建議：用 2~3 個字的短關鍵字搜尋", color = CinemaTextMuted, fontSize = 14.sp)
                        Text("例如「斗羅」而非「斗羅大陸」", color = CinemaTextMuted.copy(0.6f), fontSize = 13.sp)
                        if (uiState.query.length > 2) {
                            Spacer(Modifier.height(16.dp))
                            val shorter = uiState.query.substring(0, 2)
                            FocusableChip("試試搜尋「$shorter」") {
                                viewModel.onQueryChange(shorter)
                                viewModel.search(shorter)
                            }
                        }
                    }
                }
            }
            else -> {
                // Recent searches
                if (uiState.recentSearches.isNotEmpty()) {
                    Text("最近搜尋", color = CinemaTextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.recentSearches) { kw ->
                            FocusableChip(kw) {
                                viewModel.onQueryChange(kw)
                                viewModel.search(kw)
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("輸入關鍵字開始搜尋", color = CinemaTextMuted.copy(0.5f), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusableChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { f = it.isFocused },
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) CinemaRed else CinemaSurface,
            focusedContainerColor = CinemaRed
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (f || primary) Color.White else CinemaTextMuted,
            fontSize = 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium)
    }
}
