package com.gimy.tv.data.scraper

import com.gimy.tv.data.cache.TtlLruCache
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.EmbedSlugDao
import com.gimy.tv.data.local.entity.EmbedSlugEntity
import com.gimy.tv.data.scraper.parser.ImapleCard
import com.gimy.tv.data.scraper.parser.ImapleLine
import com.gimy.tv.data.scraper.parser.ImapleParser
import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.PlayerData
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.util.parseEpisodeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Imaple 來源。2026-10-03 起 eynytv／imaple／123kubo 是同一套新平台、同一個資料庫，
 * 合併成這一個來源，三個網域由 EndpointResolver 互為備援。
 *
 * 新平台的 id 是 cuid 字串，Vod.id 卻必須是 Long：比照 Jable／XNXX，把 cuid 雜湊成 Long，
 * 對照寫進 embed_slugs——收藏只存 id，process 重啟後要靠它找回 cuid。
 */
class ImapleTvSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
    private val embedSlugDao: EmbedSlugDao,
) : SiteSource {
    override val sourceType = SourceType.IMAPLE_TV
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    private val idToCuid = TtlLruCache<Long, String>(CUID_CACHE_CAPACITY)

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = client.fetchDocument(imapleListUrl(baseUrl, typeId, page))
            val cards = ImapleParser.parseCards(doc, baseUrl)
            val total = ImapleParser.parseListTotal(doc)
            // 有總數卻一張卡片都沒有＝版型變了。丟錯才會被健康偵測標成失效，而不是安靜地顯示空白。
            if (total != null && total > 0 && cards.isEmpty()) {
                throw ScraperException("Imaple 版型變更：共 $total 部卻解析不到卡片")
            }
            toPage(cards, page, total)
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = client.fetchDocument(imapleSearchUrl(baseUrl, keyword, page))
            // 0 筆時頁面下方仍有「你可能會喜歡」卡片，不先讀筆數就會把推薦當成搜尋結果。
            val total = ImapleParser.parseSearchTotal(doc)
                ?: throw ScraperException("Imaple 版型變更：搜尋頁找不到結果筆數")
            if (total == 0) return@withContext PaginatedResult(emptyList(), page, 0, false)
            toPage(ImapleParser.parseCards(doc, baseUrl), page, total)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail = withContext(Dispatchers.IO) {
        val cuid = cuidOrThrow(
            vodId,
            resolveSlug(
                vodId = vodId,
                fromMemory = { idToCuid.get(it) },
                fromDb = { embedSlugDao.getSlug(it, sourceType.name) },
                remember = { id, cuid -> idToCuid.put(id, cuid) },
            ),
        )
        val html = client.fetchHtml("$baseUrl/title/$cuid")
        val rsc = ImapleParser.extractRsc(html)
        val title = ImapleParser.parseTitle(rsc, baseUrl)
            ?: throw ScraperException("Imaple 版型變更：詳情頁找不到片名")
        val meta = ImapleParser.parseMeta(Jsoup.parse(html, baseUrl))
        VodDetail(
            vod = Vod(
                vodId, sourceType, title.name, title.coverUrl, meta.genre, meta.year, title.remarks,
                siteStatus = parseEpisodeStatus(title.remarks),
            ),
            director = meta.director,
            actors = meta.actors,
            synopsis = meta.synopsis,
            episodes = imapleEpisodeGroups(cuid, ImapleParser.parseLines(rsc)),
        )
    }

    override suspend fun fetchPlayerData(episodeUrl: String, deadlineMs: Long?): PlayerData =
        withContext(Dispatchers.IO) {
            val play = parseImaplePlayUrl(episodeUrl)
                ?: throw ScraperException("不是 Imaple 的播放網址：$episodeUrl")
            val html = client.fetchHtml("$baseUrl$episodeUrl", budgetMs = remainingBudget(deadlineMs))
            // 拿不到就丟錯：播放器既有的邏輯會自動換下一條線路。
            val url = ImapleParser.parseStreamUrl(ImapleParser.extractRsc(html), play.lineKey, play.episode)
                ?: throw ScraperException("此線路沒有第 ${play.episode} 集的播放網址")
            PlayerData(url, 0, play.lineKey)
        }

    override suspend fun probeListCount(baseUrl: String, profile: String?): Int = withContext(Dispatchers.IO) {
        runCatching { ImapleParser.parseCards(client.fetchDocument("$baseUrl/series"), baseUrl).size }
            .getOrDefault(0)
    }

    private suspend fun toPage(cards: List<ImapleCard>, page: Int, total: Int?): PaginatedResult<Vod> {
        val withIds = cards.map { slugStableId(it.cuid) to it }
        withIds.forEach { (id, card) -> idToCuid.put(id, card.cuid) }
        // 收藏只存 id；對照要落地，process 重啟後才找得回 cuid。寫入失敗不影響這次顯示。
        runCatching {
            embedSlugDao.insertAll(withIds.map { (id, card) -> EmbedSlugEntity(id, card.cuid, sourceType.name) })
        }
        val vods = withIds.map { (id, card) ->
            Vod(id, sourceType, card.title, card.coverUrl, "", 0, card.status, siteStatus = parseEpisodeStatus(card.status))
        }
        val size = ImapleParser.PAGE_SIZE
        val totalPages = total?.let { (it + size - 1) / size } ?: page
        return PaginatedResult(vods, page, totalPages, hasMore = total != null && page * size < total)
    }

    private companion object {
        const val CUID_CACHE_CAPACITY = 2000
    }
}

/**
 * 找不到 cuid 時丟 [ScraperException]——不可是 IOException：DetailViewModel 只對非網路錯誤呼叫
 * SavedEntryRecovery 以片名找回，丟錯型別的話改版前的舊收藏就永遠救不回來。
 */
internal fun cuidOrThrow(vodId: Long, cuid: String?): String =
    cuid ?: throw ScraperException("找不到 Imaple 影片 $vodId 的對照，可能是改版前的舊紀錄")

/** 標準分類 → 新平台路徑。typeId 先經 SiteCategoryMap 的 IMAPLE_TV 代碼轉成分類。 */
internal val IMAPLE_CATEGORY_PATHS: Map<StandardCategory, String> = mapOf(
    StandardCategory.MOVIE to "/movie",
    StandardCategory.SERIES to "/series",
    StandardCategory.ANIME to "/anime",
    StandardCategory.VARIETY to "/variety",
    StandardCategory.KOREAN to "/series?area=kr",
    StandardCategory.CHINESE to "/series?area=cn",
    StandardCategory.HK to "/series?area=hk",
    StandardCategory.TAIWAN to "/series?area=tw",
    StandardCategory.JAPANESE to "/series?area=jp",
    StandardCategory.AMERICAN to "/series?area=west",
    StandardCategory.DOCUMENTARY to "/doc",
)

internal fun imapleListUrl(baseUrl: String, typeId: Int, page: Int): String {
    val category = SourceType.IMAPLE_TV.categoryMap.categoryFor(typeId)
        ?: throw ScraperException("Imaple 沒有分類 $typeId")
    val path = IMAPLE_CATEGORY_PATHS[category] ?: throw ScraperException("Imaple 不提供 $category")
    val separator = if ('?' in path) '&' else '?'
    return "$baseUrl$path${separator}pg=${page.coerceAtLeast(1)}"
}

internal fun imapleSearchUrl(baseUrl: String, keyword: String, page: Int): String =
    "$baseUrl/search?wd=${URLEncoder.encode(keyword.trim(), "UTF-8")}&pg=${page.coerceAtLeast(1)}"

internal data class ImaplePlay(val cuid: String, val episode: Int, val lineKey: String)

internal fun imaplePlayUrl(cuid: String, episode: Int, lineKey: String): String =
    "/play/$cuid/$episode?line=${URLEncoder.encode(lineKey, "UTF-8")}"

private val IMAPLE_PLAY_PATH = Regex("""^/play/([A-Za-z0-9]+)/(\d+)\?line=(.+)$""")

internal fun parseImaplePlayUrl(url: String): ImaplePlay? {
    val m = IMAPLE_PLAY_PATH.find(url) ?: return null
    return ImaplePlay(m.groupValues[1], m.groupValues[2].toInt(), URLDecoder.decode(m.groupValues[3], "UTF-8"))
}

private val ZERO_EPISODE_NAME = Regex("""第\s*0+\s*集""")

/**
 * 每條線路一個 EpisodeGroup。集數依位置編號：有些來源的集名是「第 0 集」這種錯的。
 * sourceId 用 1 起的小整數——跨來源增益以 -(ordinal*100 + lineId + 1) 推導負數 id，lineId 必須小於 100。
 */
internal fun imapleEpisodeGroups(cuid: String, lines: List<ImapleLine>): List<EpisodeGroup> {
    if (lines.isEmpty()) throw ScraperException("這部片沒有可播放的線路")
    return lines.mapIndexed { i, line ->
        EpisodeGroup(
            sourceName = line.name,
            sourceId = i + 1,
            episodes = line.episodeNames.mapIndexed { j, name ->
                val number = j + 1
                val title = if (name.isBlank() || ZERO_EPISODE_NAME.matches(name.trim())) {
                    "第${number.toString().padStart(2, '0')}集"
                } else {
                    name
                }
                Episode(number, title, imaplePlayUrl(cuid, number, line.lineKey))
            },
        )
    }
}
