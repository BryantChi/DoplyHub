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
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshIconButton
import com.gimy.tv.ui.components.RefreshLoadingBar
import com.gimy.tv.ui.components.RefreshableContainer
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
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
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
            .imePadding()
            .padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)
    ) {
        // ── Search bar ──
        // Phone: drop the「返回」chip (system back covers it via BackHandler above) and
        // move ✕ inside the TextField as a trailingIcon. Without this, on a 360dp phone
        // the row was 「返回」/spacer/TextField/spacer/✕/spacer/搜尋/spacer/Refresh —
        // the weight(1f) TextField got squeezed to ~150dp wide.
        // TV keeps the explicit「返回」chip because remote nav benefits from a focusable target.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isTV) {
                FocusableChip("返回") {
                    if (uiState.hasSearched) viewModel.clearResults() else onBack()
                }
                Spacer(Modifier.width(12.dp))
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = {
                    androidx.compose.material3.Text(
                        "輸入關鍵字搜尋…",
                        color = CinemaTextMuted,
                        fontSize = 14.sp,
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.search()
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
                trailingIcon = if (uiState.query.isNotEmpty()) {
                    {
                        androidx.compose.material3.IconButton(onClick = {
                            viewModel.onQueryChange("")
                            inputFocusRequester.requestFocus()
                        }) {
                            androidx.compose.material3.Text(
                                "✕",
                                color = CinemaTextMuted,
                                fontSize = 16.sp,
                            )
                        }
                    }
                } else null,
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
                    .height(if (isTV) 52.dp else 56.dp)
                    .focusRequester(inputFocusRequester)
            )

            Spacer(Modifier.width(8.dp))

            FocusableChip("搜尋", primary = true) {
                viewModel.search()
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            Spacer(Modifier.width(4.dp))
            RefreshIconButton(
                isRefreshing = uiState.isRefreshing,
                onClick = { viewModel.refresh() },
                enabled = uiState.query.isNotBlank() && uiState.hasSearched,
            )
        }
        RefreshLoadingBar(uiState.isRefreshing)

        Spacer(Modifier.height(20.dp))

        // ── Content area ──
        when {
            uiState.isSearching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DoplyLoadingIndicator(dims.loadingIndicatorSize)
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
                val searchIsAtTop by remember {
                    derivedStateOf {
                        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                    }
                }

                // Auto load more when scrolling near bottom
                LaunchedEffect(gridState) {
                    snapshotFlow {
                        val info = gridState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val total = info.totalItemsCount
                        lastVisible to total
                    }.collect { (lastVisible, total) ->
                        if (total > 0 && lastVisible >= total - 4) {
                            viewModel.loadMore()
                        }
                    }
                }

                // Per-source counts for chip filter (Phase 3.2)
                val sourceCounts = remember(uiState.results) {
                    uiState.results.groupBy { it.sourceType }.mapValues { it.value.size }
                }
                var selectedSource by remember { mutableStateOf<SourceType?>(null) }
                // Reset chip when query changes
                LaunchedEffect(uiState.query) { selectedSource = null }
                val filteredResults = remember(uiState.results, selectedSource) {
                    if (selectedSource == null) uiState.results
                    else uiState.results.filter { it.sourceType == selectedSource }
                }

                RefreshableContainer(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    enabled = searchIsAtTop,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Text("找到 ${filteredResults.size} 個結果（總 ${uiState.results.size}）",
                            color = CinemaTextMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp))

                        // Source filter chips: 「全部 N」 + 每來源 count（只列出有結果的）
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            item(key = "chip_all") {
                                SourceFilterChip(
                                    label = "全部 ${uiState.results.size}",
                                    selected = selectedSource == null,
                                    onClick = { selectedSource = null },
                                )
                            }
                            items(SourceType.values().filter { (sourceCounts[it] ?: 0) > 0 },
                                key = { "chip_${it.name}" }) { src ->
                                val count = sourceCounts[src] ?: 0
                                SourceFilterChip(
                                    label = "${src.displayName} $count",
                                    selected = selectedSource == src,
                                    onClick = { selectedSource = if (selectedSource == src) null else src },
                                )
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(dims.gridMinCellWidth),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(filteredResults, key = { "${it.sourceType}_${it.id}" }) { vod ->
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
                            DoplyLoadingIndicator(dims.loadingIndicatorSize)
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
private fun SourceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }
    val container = when {
        selected -> CinemaRed
        f -> CinemaRed.copy(0.6f)
        else -> CinemaSurface
    }
    val textColor = if (selected || f) Color.White else CinemaTextMuted
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
            colors = ButtonDefaults.colors(containerColor = container, focusedContainerColor = CinemaRed),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
        ) { Text(label, color = textColor, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = container, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
        ) { Text(label, color = textColor, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusableChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }
    val chipColor = if (primary) CinemaRed else CinemaSurface
    val textColor = if (f || primary) Color.White else CinemaTextMuted
    val textWeight = if (primary) FontWeight.Bold else FontWeight.Medium
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(containerColor = chipColor, focusedContainerColor = CinemaRed),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
        ) { Text(label, color = textColor, fontSize = 14.sp, fontWeight = textWeight) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = chipColor, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
        ) { Text(label, color = if (primary) Color.White else CinemaTextMuted, fontSize = 14.sp, fontWeight = textWeight) }
    }
}
