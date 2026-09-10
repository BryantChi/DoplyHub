package com.gimy.tv.data.cleanup

import com.gimy.tv.data.endpoint.EndpointHealthStatus
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.FavoriteDao
import com.gimy.tv.data.local.dao.WatchHistoryDao
import com.gimy.tv.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether saved entries can still be opened, and retires the ones that clearly cannot.
 *
 * Only a *healthy* source can condemn an entry — see [shouldAutoRemove]. A source that is
 * itself broken (or simply unreachable on this network) makes every entry fail, and deleting
 * on that basis would destroy data over a temporary fault.
 */
@Singleton
class StaleEntryTracker @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val endpointResolver: EndpointResolver,
) {
    /**
     * Records the outcome of opening [vodId]. A success clears the counter; repeated failures
     * mark the entry stale and eventually retire it.
     */
    suspend fun recordOpenResult(sourceType: SourceType, vodId: Long, opened: Boolean) {
        val source = sourceType.name
        val status = endpointResolver.health.value[sourceType]?.status ?: EndpointHealthStatus.UNKNOWN

        runCatching {
            favoriteDao.missCountOf(vodId, source)?.let { current ->
                applyTo(current, opened, status,
                    remove = { favoriteDao.delete(vodId, source) },
                    update = { favoriteDao.updateMissCount(vodId, source, it) })
            }
        }
        runCatching {
            watchHistoryDao.missCountOf(vodId, source)?.let { current ->
                applyTo(current, opened, status,
                    remove = { watchHistoryDao.deleteStaleRow(vodId, source) },
                    update = { watchHistoryDao.updateMissCount(vodId, source, it) })
            }
        }
    }

    private suspend fun applyTo(
        current: Int,
        opened: Boolean,
        status: EndpointHealthStatus,
        remove: suspend () -> Unit,
        update: suspend (Int) -> Unit,
    ) {
        val next = nextMissCount(current, opened)
        if (next == current) return
        if (shouldAutoRemove(next, STALE_REMOVAL_THRESHOLD, status)) remove() else update(next)
    }

    /** Manual "clear stale entries" action. Returns how many rows went. */
    suspend fun clearStale(): Int {
        val a = runCatching { favoriteDao.deleteStale() }.getOrDefault(0)
        val b = runCatching { watchHistoryDao.deleteStale() }.getOrDefault(0)
        return a + b
    }
}
