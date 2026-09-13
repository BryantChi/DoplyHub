package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.*

interface SiteSource {
    val sourceType: SourceType
    val baseUrl: String

    suspend fun fetchCategories(): List<Category>
    suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod>
    suspend fun fetchVodDetail(vodId: Long): VodDetail
    /**
     * 取得這一集的播放位址。
     *
     * [deadlineMs] 是 `System.currentTimeMillis()` 基準的絕對截止時間，null 代表不設限。
     * 取流會在多條線路之間輪流嘗試，一條卡住就得盡快換下一條；而 coroutine 的 withTimeout
     * 中斷不了阻塞的 execute()，所以上層宣告的秒數必須一路傳到 OkHttp 的 call 上才算數。
     */
    suspend fun fetchPlayerData(episodeUrl: String, deadlineMs: Long? = null): PlayerData
    suspend fun search(keyword: String, page: Int): PaginatedResult<Vod>

    /**
     * Probe the given baseUrl by fetching its default-category list page (page 1) and
     * parsing it with THIS source's parser; returns the number of items parsed.
     * Health-probe use — decoupled from [baseUrl]/EndpointResolver (does NOT call getBaseUrl).
     * Default -1 means "probe unsupported" → callers fall back to a HEAD 200 check.
     */
    /**
     * A URL that reliably triggers this site's Cloudflare challenge, or null when the source
     * is not behind one. Used to solve at launch so the first search does not lose this
     * source to the aggregator's 5s timeout.
     */
    val cloudflareWarmUpUrl: String? get() = null

    /**
     * Item count parsed from [baseUrl]'s list page; -1 when unsupported.
     *
     * [profile] identifies the mirror's template so a source with several differently-shaped
     * mirrors probes each with its own paths — probing a poster mirror with card paths would
     * return 0 and wrongly mark a healthy endpoint as broken.
     */
    suspend fun probeListCount(baseUrl: String, profile: String? = null): Int = -1
}
