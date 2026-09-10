package com.gimy.tv.data.cleanup

import com.gimy.tv.data.endpoint.EndpointHealthStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Auto-removal deletes the user's own favourites and history with no undo, so the bar is
 * deliberately high.
 *
 * The MovieFFM incident is the cautionary case: a DNS filter on the local network made every
 * request fail with an SSL error while the site itself was perfectly fine. Had removal keyed
 * on "fetch failed", one launch on that network would have wiped those entries. Hence the
 * requirement that the SOURCE be provably healthy before any individual entry is judged gone.
 */
class StaleEntryPolicyTest {

    private val threshold = 3

    @Test fun `removes only after repeated misses on a healthy source`() {
        assertThat(shouldAutoRemove(3, threshold, EndpointHealthStatus.HEALTHY)).isTrue()
        assertThat(shouldAutoRemove(4, threshold, EndpointHealthStatus.HEALTHY)).isTrue()
    }

    @Test fun `below the threshold nothing is removed`() {
        assertThat(shouldAutoRemove(2, threshold, EndpointHealthStatus.HEALTHY)).isFalse()
        assertThat(shouldAutoRemove(0, threshold, EndpointHealthStatus.HEALTHY)).isFalse()
    }

    /** The MovieFFM/DNS scenario: the whole source is down, so misses prove nothing. */
    @Test fun `a broken source never triggers removal`() {
        assertThat(shouldAutoRemove(99, threshold, EndpointHealthStatus.BROKEN)).isFalse()
    }

    @Test fun `a degraded source never triggers removal`() {
        assertThat(shouldAutoRemove(99, threshold, EndpointHealthStatus.DEGRADED)).isFalse()
    }

    /** No probe result means no evidence — refuse to delete on a guess. */
    @Test fun `an unprobed source never triggers removal`() {
        assertThat(shouldAutoRemove(99, threshold, EndpointHealthStatus.UNKNOWN)).isFalse()
    }

    // ── miss counting ──

    @Test fun `a successful open resets the counter`() {
        assertThat(nextMissCount(current = 2, opened = true)).isEqualTo(0)
    }

    @Test fun `a failed open increments the counter`() {
        assertThat(nextMissCount(current = 2, opened = false)).isEqualTo(3)
    }

    /** Marked stale for the UI as soon as it misses, well before it is eligible for removal. */
    @Test fun `one miss already marks the entry stale`() {
        assertThat(isStale(missCount = 1)).isTrue()
        assertThat(isStale(missCount = 0)).isFalse()
    }
}
