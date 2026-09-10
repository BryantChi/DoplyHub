package com.gimy.tv.data.network

import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a foreground request may wait for a Cloudflare solve.
 *
 * Aggregated search allows each source 5s, so anything longer is wasted: search has already
 * moved on, yet the blocked thread still occupies an OkHttp dispatcher slot and slows the
 * remaining sources down.
 */
const val FOREGROUND_SOLVE_BUDGET_MS = 2_500L

/**
 * Waits up to [budgetMs] for [solve], handing the request back rather than blocking for the
 * full challenge.
 *
 * On timeout the solve is restarted through [continueInBackground] so the *next* request
 * benefits — the alternative, parking the caller for up to 35 seconds (or behind a startup
 * warm-up holding the per-host lock), is what made search appear to hang while still
 * returning fewer sources.
 *
 * A thrown solve degrades to false so a missing WebView never breaks the request path.
 */
internal suspend fun awaitSolveWithin(
    budgetMs: Long,
    solve: suspend () -> Boolean,
    continueInBackground: () -> Unit,
): Boolean {
    val result = withTimeoutOrNull(budgetMs) { runCatching { solve() }.getOrDefault(false) }
    if (result == null) continueInBackground()
    return result ?: false
}
