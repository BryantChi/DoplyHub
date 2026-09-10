package com.gimy.tv.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A challenge solve takes 6-35s, while aggregated search gives each source 5s. The
 * interceptor used to block its thread for the whole solve: search had already abandoned the
 * source, yet the thread stayed parked, holding an OkHttp dispatcher slot and slowing every
 * other source down. Worse, a startup warm-up holding the per-host lock made a user's search
 * queue behind it.
 *
 * So the foreground request gets a budget. Exceeding it must free the request immediately
 * while the solve keeps running for the *next* request to benefit from.
 */
class SolveBudgetTest {

    @Test fun `returns true when the solve finishes inside the budget`() = runTest {
        var backgrounded = false
        val ok = awaitSolveWithin(
            budgetMs = 1_000,
            solve = { delay(100); true },
            continueInBackground = { backgrounded = true },
        )
        assertThat(ok).isTrue()
        assertThat(backgrounded).isFalse()
    }

    /** The key behaviour: a slow solve must not hold the request. */
    @Test fun `gives up at the budget instead of waiting for the full solve`() = runTest {
        var backgrounded = false
        val ok = awaitSolveWithin(
            budgetMs = 1_000,
            solve = { delay(30_000); true },
            continueInBackground = { backgrounded = true },
        )
        assertThat(ok).isFalse()
        assertThat(backgrounded).isTrue()
    }

    @Test fun `a failed solve inside the budget is not retried in the background`() = runTest {
        var backgrounded = false
        val ok = awaitSolveWithin(
            budgetMs = 1_000,
            solve = { delay(50); false },
            continueInBackground = { backgrounded = true },
        )
        assertThat(ok).isFalse()
        assertThat(backgrounded).isFalse()
    }

    /** A thrown solve must not propagate into the interceptor and kill the request. */
    @Test fun `an exception degrades to false`() = runTest {
        val ok = awaitSolveWithin(
            budgetMs = 1_000,
            solve = { error("webview unavailable") },
            continueInBackground = {},
        )
        assertThat(ok).isFalse()
    }
}
