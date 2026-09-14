package com.gimy.tv.domain.repository

import kotlinx.coroutines.flow.StateFlow
import com.gimy.tv.domain.model.UpdateState
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.flow.Flow

data class HomeRowData(
    val title: String,
    val sourceType: SourceType,
    val typeId: Int,
    val items: List<Vod>
)

interface VodRepository {
    suspend fun getVodList(sourceType: SourceType, typeId: Int, page: Int): PaginatedResult<Vod>
    suspend fun getVodDetail(sourceType: SourceType, vodId: Long): VodDetail
    /**
     * 取得播放位址。[budgetMs] 是這一次取流願意等多久，null 代表照用網路層的預設上限。
     *
     * 會傳這個值是因為取流會在多條線路之間輪流嘗試，一條卡住就得盡快換下一條；
     * 而 coroutine 的 withTimeout 中斷不了阻塞的 HTTP 呼叫，秒數得一路傳到 OkHttp 才算數。
     */
    suspend fun getPlayerData(
        sourceType: SourceType, episodeUrl: String, budgetMs: Long? = null,
    ): PlayerData
    suspend fun search(sourceType: SourceType, keyword: String, page: Int): PaginatedResult<Vod>

    // Series search
    suspend fun searchSeriesVods(vod: Vod): List<Vod>

    // Multi-source integration
    suspend fun searchAllSources(keyword: String, page: Int): PaginatedResult<Vod>
    suspend fun getEnrichedVodDetail(
        sourceType: SourceType,
        vodId: Long,
        cachedPrimary: VodDetail? = null,
        /** Bypass the in-memory detail cache when set. Used by user-initiated refresh
         *  so a stale 60s-cached result doesn't shadow the fresh fetch. */
        forceRefresh: Boolean = false,
    ): VodDetail
    suspend fun getGimyHomeRows(forceRefresh: Boolean = false): List<HomeRowData>
    suspend fun getMovieffmHomeRows(forceRefresh: Boolean = false): List<HomeRowData>

    /** 丟掉記憶體內的首頁／搜尋／詳情快取。清除快取與重試路徑用，讓下一次取用真的重打。 */
    fun clearMemoryCaches()

    /**
     * 依片名找出同一部片，用於舊 id 失效後的復原。
     *
     * 會分別回報「有沒有找到」與「[preferredSource] 這次到底有沒有回應」，因為呼叫端
     * 要靠後者分辨「這部片真的沒了」與「只是連不上」。
     */
    suspend fun findByTitle(title: String, preferredSource: SourceType): TitleLookup
}

interface FavoriteRepository {
    /** Main favorites list — never includes adult records (JABLE_TV / XNXX / FORUM5278). */
    fun getFavorites(): Flow<List<Vod>>
    /** Adult-only favorites list — surfaced inside the 18+ zone only. */
    fun getAdultFavorites(): Flow<List<Vod>>
    fun isFavorite(vodId: Long, sourceType: SourceType): Flow<Boolean>
    suspend fun addFavorite(vod: Vod)
    suspend fun removeFavorite(vodId: Long, sourceType: SourceType)

    /** 開不起來的筆數。讓使用者自己決定要不要清，而不是靠保守的自動退役。 */
    fun staleCount(): Flow<Int>
    suspend fun clearStale()
}

interface WatchHistoryRepository {
    /** Main recent list — never includes adult records. */
    fun getRecentHistory(limit: Int = 20): Flow<List<WatchHistoryEntry>>
    /** Adult-only recent list — surfaced inside the 18+ zone only. */
    fun getRecentAdultHistory(limit: Int = 50): Flow<List<WatchHistoryEntry>>
    suspend fun getProgress(vodId: Long, sourceType: SourceType): WatchHistoryEntry?
    /** 同 [getProgress] 但會持續推送。看完一集返回詳情頁時，一次性查詢拿到的是舊值。 */
    fun observeProgress(vodId: Long, sourceType: SourceType): Flow<WatchHistoryEntry?>
    suspend fun saveProgress(entry: WatchHistoryEntry)
    suspend fun deleteEntry(vodId: Long, sourceType: SourceType)
    suspend fun clearHistory()
    suspend fun clearAdultHistory()

    /** 開不起來的筆數，語意同 [FavoriteRepository.staleCount]。 */
    fun staleCount(): Flow<Int>
    suspend fun clearStale()
}

/** [VodRepository.findByTitle] 的結果。[sourceAnswered] 為 false 代表原來源這次沒回應，
 *  找不到不能當成「這部片不存在」。 */
data class TitleLookup(val match: Vod?, val sourceAnswered: Boolean)

data class WatchHistoryEntry(
    val vodId: Long,
    /** Primary scraper the user came from (where they entered detail/list). */
    val sourceType: SourceType,
    val title: String,
    val coverUrl: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val sourceId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Actual scraper whose line played (may differ from [sourceType] when fallback /
     *  user-switch lands on a cross-source enriched line). null = primary line played
     *  or pre-v2.5.3 row that never recorded this. Used by 「繼續觀看」 to route back to
     *  the right line on next visit. */
    val playedSourceType: SourceType? = null,
    /** Episode kind marker carried over from [Episode.kind]. null = main-line; pre-v2.5.4
     *  rows also use null. Persisted so progress for "OAD 5" doesn't silently overwrite
     *  progress for the regular ep5 (and vice versa). */
    val episodeKind: String? = null,
)

interface SearchHistoryRepository {
    fun getRecentSearches(limit: Int = 20): Flow<List<String>>
    suspend fun addSearch(keyword: String)
    suspend fun removeSearch(keyword: String)
    suspend fun clearSearches()
}

/**
 * 成人進階區的清單來源。
 *
 * jable／xnxx 用路徑、5278 用版面編號，三者不吃 typeId 那一套，所以沒有走 VodRepository。
 * 但這不代表畫面該直接注入三個 scraper——同一段 `when (sourceType)` 分派原本在
 * AdultPlusViewModel 與 AdultPlusBrowseViewModel 各寫一次，新增來源要改兩個地方，
 * 而 UI 層也因此 import 了資料層的具體實作。
 */
interface AdultPlusCatalog {
    /** [pathKey] 是該來源自己的清單鍵：jable／xnxx 是路徑，5278 是 "forum:{id}"。 */
    suspend fun fetchByPath(
        sourceType: SourceType, pathKey: String, page: Int,
    ): PaginatedResult<Vod>
}

/**
 * 使用者啟用了哪些來源。
 *
 * 介面放在 domain、實作放在 data，是為了讓畫面只認得這份契約——
 * 不然 ViewModel 會直接綁上 DataStore 的實作類別。
 */
interface SourcePreferences {
    val enabledSources: StateFlow<Set<SourceType>>

    suspend fun setEnabled(sourceType: SourceType, enabled: Boolean)

    /**
     * 等 DataStore 真的讀完才回傳。
     *
     * 冷啟動時直接讀 [enabledSources].value 拿到的是 stateIn 的初始佔位值（全部來源），
     * 會把錯的來源集合寫進搜尋快取。
     */
    suspend fun snapshot(): Set<SourceType>
}

/**
 * 成人區的開關、PIN 與鎖定狀態。
 *
 * [unlocked] 只存在記憶體，冷啟動一定回到鎖定——即使這次開機前已經解過鎖。
 */
interface AdultContentPreferences {
    val enabled: StateFlow<Boolean>
    val pinRequired: StateFlow<Boolean>
    val pinHash: StateFlow<String?>
    val failCount: StateFlow<Int>
    val lockedUntilMs: StateFlow<Long>
    val adultPlusEnabled: StateFlow<Boolean>
    val unlocked: StateFlow<Boolean>

    /**
     * 一次性讀取，給 Application.onCreate 這種 StateFlow 還沒就緒的時間點用。
     * 直接讀 [adultPlusEnabled].value 在那個時機幾乎必定讀到初始佔位值 false。
     */
    suspend fun isAdultPlusEnabledNow(): Boolean

    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean
    fun remainingLockSeconds(nowMs: Long = System.currentTimeMillis()): Long

    suspend fun setEnabled(value: Boolean)
    suspend fun setAdultPlusEnabled(value: Boolean)
    suspend fun setPinRequired(value: Boolean)
    suspend fun setPin(pin: String)
    suspend fun clearPin()

    /** 對一次即解鎖本次工作階段；連續錯到上限會觸發鎖定計時。 */
    suspend fun verifyPin(input: String): Boolean

    fun markUnlocked()
    suspend fun resetAll()
}

/**
 * App 自我更新。
 *
 * [launchInstaller] 與 [requestInstallPermission] 都會開系統畫面，成功與否由回傳值表示，
 * 呼叫端據此決定要不要顯示提示——這兩件事在不同 Android 版本與機型上都可能直接失敗。
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>

    fun checkForUpdate(silent: Boolean = false)
    fun dismiss()
    fun startDownload()
    fun cancelDownload()

    /** @return false 代表沒有可安裝的檔案，狀態維持不變。 */
    fun launchInstaller(): Boolean

    /** @return false 代表這台裝置沒有「安裝未知應用程式」的設定頁可開。 */
    fun requestInstallPermission(): Boolean
}

/**
 * 清快取。
 *
 * 三個畫面都會用到，但各自要清的範圍不同：設定頁是整套、首頁與詳情頁只清網路回應，
 * 不動已經抓下來的封面圖（重抓幾十張圖在電視盒上很痛）。
 */
interface CacheManager {
    /** @return 是否在時間內等到端點重新探測的結果；false 代表還在背景跑。 */
    suspend fun clearAll(
        reresolveEndpoints: Boolean = true,
        clearImages: Boolean = true,
    ): Boolean
}

/** 各來源目前選中的網址健不健康，設定頁的「來源管理」直接顯示它。 */
interface SourceHealthMonitor {
    val health: StateFlow<Map<SourceType, EndpointHealth>>
}

/**
 * 正在解 Cloudflare 挑戰的主機。
 *
 * 搜尋畫面靠它說出「正在驗證中」，而不是安靜地少回幾個來源——那會讓使用者
 * 以為這些站沒有這部片。
 */
interface ChallengeSolverStatus {
    val solvingHosts: StateFlow<Set<String>>
}

/** 記錄一筆收藏／紀錄這次開得起來還是開不起來，累積到一定次數才會被視為失效。 */
interface StaleEntryReporter {
    suspend fun recordOpenResult(sourceType: SourceType, vodId: Long, opened: Boolean)
}

/** 舊記錄開不起來時，試著找回同一部片。 */
interface SavedEntryRecoverer {
    suspend fun recover(sourceType: SourceType, vodId: Long): RecoveryPlan
}
