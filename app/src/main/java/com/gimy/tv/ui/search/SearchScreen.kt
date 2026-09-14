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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gimy.tv.ui.components.FocusableChip
import com.gimy.tv.ui.components.DoplyButton
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
import com.gimy.tv.domain.model.idFor
import com.gimy.tv.domain.model.searchSources
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                FocusableChip(stringResource(R.string.common_back)) {
                    if (uiState.hasSearched) viewModel.clearResults() else onBack()
                }
                Spacer(Modifier.width(12.dp))
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = {
                    androidx.compose.material3.Text(
                        stringResource(R.string.search_hint),
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

            FocusableChip(stringResource(R.string.nav_search), primary = true) {
                viewModel.search()
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            // Visible only after a search has run — clears the result grid back to
            // the recent-searches/empty state. Touch users on phone previously had
            // no way to do this without system back; TV's「返回」chip handled it.
            if (uiState.hasSearched) {
                Spacer(Modifier.width(4.dp))
                FocusableChip(stringResource(R.string.common_clear)) {
                    viewModel.clearResults()
                    viewModel.onQueryChange("")
                    inputFocusRequester.requestFocus()
                }
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
                        Text(
                            if (uiState.isVerifying) stringResource(R.string.search_first_time_verify)
                            else stringResource(R.string.search_searching, uiState.query),
                            color = CinemaTextMuted, fontSize = 14.sp,
                        )
                    }
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.search_failed), color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(uiState.error ?: "", color = CinemaTextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        FocusableChip(stringResource(R.string.common_retry)) { viewModel.search() }
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

                // 依「哪些來源有這部片」計數，而不是卡片掛在誰名下。同一部片被多個站收錄時
                // 只會出一張卡，若只算歸屬來源，排在合併順序後面的站（例如 Eyny）幾乎永遠
                // 不會出現在篩選裡，即使它確實有那部片。
                val sourceCounts = remember(uiState.results) {
                    val counts = mutableMapOf<SourceType, Int>()
                    uiState.results.forEach { vod ->
                        vod.searchSources().forEach { counts[it] = (counts[it] ?: 0) + 1 }
                    }
                    counts.toMap()
                }
                var selectedSource by remember { mutableStateOf<SourceType?>(null) }
                // Reset chip when query changes
                LaunchedEffect(uiState.query) { selectedSource = null }
                val filteredResults = remember(uiState.results, selectedSource) {
                    if (selectedSource == null) uiState.results
                    else uiState.results.filter { selectedSource in it.searchSources() }
                }

                RefreshableContainer(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    enabled = searchIsAtTop,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Text(stringResource(R.string.search_result_count, filteredResults.size, uiState.results.size),
                            color = CinemaTextMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp))

                        // Source filter chips: 「全部 N」 + 每來源 count（只列出有結果的）
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            item(key = "chip_all") {
                                SourceFilterChip(
                                    label = stringResource(R.string.search_filter_all, uiState.results.size),
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
                                // 篩了某個來源就開那個來源的版本——各站的 id 空間互不相通，
                                // 用歸屬來源的 id 會開到另一部片或直接 404。角標也跟著換，
                                // 否則篩了 Eyny 卻整排顯示 GTV，看起來像篩選沒生效。
                                val src = selectedSource ?: vod.sourceType
                                val shown = if (src == vod.sourceType) vod else vod.copy(sourceType = src)
                                VodCard(shown, onClick = { onVodClick(src, vod.idFor(src)) })
                            }
                        }
                        if (!uiState.hasMore && !uiState.isLoadingMore) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.search_all_shown), color = CinemaTextMuted.copy(0.6f), fontSize = 13.sp)
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
            // 一個來源都沒回應時，空結果的原因不是「沒有這部片」，叫使用者換關鍵字
            // 只會讓他一直換一直搜。這裡要講的是「連不上，請重試」。
            uiState.hasSearched && uiState.allSourcesFailed -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.search_all_sources_down), color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.search_all_sources_down_desc), color = CinemaTextMuted, fontSize = 14.sp)
                        Text(stringResource(R.string.search_all_sources_down_hint), color = CinemaTextMuted.copy(0.6f), fontSize = 13.sp)
                    }
                }
            }
            uiState.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.search_not_found, uiState.query), color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.search_tip_short_keyword), color = CinemaTextMuted, fontSize = 14.sp)
                        Text(stringResource(R.string.search_tip_example), color = CinemaTextMuted.copy(0.6f), fontSize = 13.sp)
                        if (uiState.query.length > 2) {
                            Spacer(Modifier.height(16.dp))
                            val shorter = uiState.query.substring(0, 2)
                            FocusableChip(stringResource(R.string.search_try_shorter, shorter)) {
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.search_recent), color = CinemaTextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        // Clear-all action. The previous build had no way to remove
                        // recent searches on phone (TV remote could long-press a chip
                        // via Compose-TV defaults but that doesn't reach mobile users).
                        // Surfacing a clear chip here covers both — touch users tap it,
                        // remote users focus → click.
                        FocusableChip(stringResource(R.string.common_clear)) { viewModel.clearRecentSearches() }
                    }
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
                        Text(stringResource(R.string.search_empty), color = CinemaTextMuted.copy(0.5f), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // 原本自己接 onFocusChanged 算出「有焦點時用 CinemaRed.copy(0.6f)」，但兩個分支都
    // 同時設了 focusedContainerColor = CinemaRed，焦點狀態一律蓋過去，那個半透明色其實
    // 從來沒被畫出來過。焦點顏色交給 DoplyButton 表達即可。
    DoplyButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        containerColor = if (selected) CinemaRed else CinemaSurface,
        contentColor = if (selected) Color.White else CinemaTextMuted,
        focusedContainerColor = CinemaRed,
        focusedContentColor = Color.White,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
        focusBorder = false,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

