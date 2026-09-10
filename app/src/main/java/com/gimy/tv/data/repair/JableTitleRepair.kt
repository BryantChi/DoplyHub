package com.gimy.tv.data.repair

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.gimy.tv.data.local.dao.FavoriteDao
import com.gimy.tv.data.local.dao.WatchHistoryDao
import com.gimy.tv.data.scraper.JableTvSource
import com.gimy.tv.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.repairDataStore: DataStore<Preferences> by preferencesDataStore(name = "repair_flags")

/**
 * One-off repair for favourites/history saved before the jable detail-title fix.
 *
 * Those rows recorded the first recommendation card's title instead of the video's own, and
 * the stored value cannot be corrected without refetching — so this walks the jable rows
 * once and rewrites the ones that come back different.
 *
 * Two deliberate choices:
 *  - Any failed row leaves the repair unmarked. A row whose slug is unknown cannot be
 *    resolved yet — the slug map only started being persisted in this same release — and a
 *    Cloudflare challenge that has not cleared yet fails the same way. Leaving the flag
 *    unset lets a later launch finish the job instead of giving up permanently.
 *  - Requests are serialised with a gap. These sites rate-limit, and a burst at startup on
 *    behalf of a screen the user may not even open is not worth it.
 */
@Singleton
class JableTitleRepair @Inject constructor(
    @ApplicationContext context: Context,
    private val favoriteDao: FavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val jableTvSource: JableTvSource,
) {
    private val dataStore = context.repairDataStore
    private val source = SourceType.JABLE_TV.name

    suspend fun repairOnce() {
        if (runCatching { dataStore.data.first()[DONE_KEY] }.getOrNull() == true) return

        val favorites = runCatching { favoriteDao.getBySource(source) }.getOrDefault(emptyList())
        val history = runCatching { watchHistoryDao.getBySource(source) }.getOrDefault(emptyList())
        if (favorites.isEmpty() && history.isEmpty()) {
            markDone()
            return
        }

        // One fetch per video, even when it appears in both tables.
        val titlesByVodId = mutableMapOf<Long, String>()
        val vodIds = (favorites.map { it.vodId } + history.map { it.vodId }).distinct()
        var hadFailures = false

        for (vodId in vodIds) {
            val fetched = runCatching { jableTvSource.fetchVodDetail(vodId).vod.title }
                .getOrElse { hadFailures = true; null }
            if (fetched != null) titlesByVodId[vodId] = fetched
            delay(REQUEST_GAP_MS)
        }

        favorites.forEach { row ->
            val fetched = titlesByVodId[row.vodId]
            if (shouldReplaceTitle(row.title, fetched)) {
                runCatching { favoriteDao.updateTitle(row.vodId, source, fetched!!.trim()) }
            }
        }
        history.forEach { row ->
            val fetched = titlesByVodId[row.vodId]
            if (shouldReplaceTitle(row.title, fetched)) {
                runCatching { watchHistoryDao.updateTitle(row.vodId, source, fetched!!.trim()) }
            }
        }

        if (!hadFailures) markDone()
    }

    private suspend fun markDone() {
        runCatching { dataStore.edit { it[DONE_KEY] = true } }
    }

    private companion object {
        val DONE_KEY = booleanPreferencesKey("jable_title_repair_done")
        const val REQUEST_GAP_MS = 1_200L
    }
}
