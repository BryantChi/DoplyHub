package com.gimy.tv.data.cleanup

import com.gimy.tv.data.endpoint.EndpointHealthStatus

/** Consecutive failed opens before an entry is eligible for automatic removal. */
const val STALE_REMOVAL_THRESHOLD = 3

/**
 * Whether a saved entry may be deleted automatically.
 *
 * Deletion is irreversible and the data is the user's own, so a repeated miss alone is not
 * enough — the source itself must be provably healthy. Otherwise a local network fault would
 * look identical to a dead entry: a DNS filter once made every MovieFFM request fail with an
 * SSL error while the site was fine, and keying on "fetch failed" would have wiped those rows
 * on the next launch.
 */
internal fun shouldAutoRemove(
    missCount: Int,
    threshold: Int,
    sourceStatus: EndpointHealthStatus,
): Boolean = sourceStatus == EndpointHealthStatus.HEALTHY && missCount >= threshold

/** Miss counter after an open attempt; any success clears the history of misses. */
internal fun nextMissCount(current: Int, opened: Boolean): Int = if (opened) 0 else current + 1

/**
 * Whether to show the entry as stale in the UI.
 *
 * Deliberately looser than [shouldAutoRemove]: one miss is enough to grey it out and offer a
 * manual clear, long before the app would delete anything on its own.
 */
internal fun isStale(missCount: Int): Boolean = missCount > 0
