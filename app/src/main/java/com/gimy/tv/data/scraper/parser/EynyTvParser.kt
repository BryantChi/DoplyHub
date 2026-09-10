package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

object EynyTvParser {
    private val vodIdRegex = Regex("/voddetail/(\\d+)\\.html")

    /**
     * 搜尋頁的解析。**不能沿用 [parseVodList]**：搜尋頁與列表頁是不同模板，卡片分別是
     * `.module-search-item` 與 `.module-item`，實測搜尋頁上 `.module-item` 數量為 0。
     * 原本 search() 直接呼叫 parseVodList，所以 Eyny 每次搜尋都回 0 筆、在聚合結果裡
     * 靜默缺席，看起來就像這個站沒有這部片。
     */
    fun parseSearchResults(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .myui-side, aside").remove()
        val items = mutableListOf<Vod>()
        for (item in doc.select(".module-search-item")) {
            val href = item.selectFirst("a[href*=/voddetail/]")?.attr("href").orEmpty()
            val id = vodIdRegex.find(href)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = item.selectFirst("h3 a")?.text()?.trim().orEmpty()
                .ifBlank { item.selectFirst("a[href*=/voddetail/]")?.attr("title")?.trim().orEmpty() }
            if (title.isBlank()) continue
            // src 是 lazyload 的佔位圖（loading.png），真正的封面在 data-src。取錯不會有
            // 任何錯誤跡象，只會整頁變成同一張灰圖。
            val img = item.selectFirst("img")
            val cover = resolveUrl(
                img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-original").orEmpty() },
                baseUrl,
            )
            val status = item.selectFirst(".video-serial")?.text()?.trim().orEmpty()
            @Suppress("DEPRECATION")
            items.add(Vod(id, SourceType.EYNY_TV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a:contains(下一页), a.next").isNotEmpty()
        return PaginatedResult(unique, page, if (hasNext) page + 1 else page, hasNext)
    }

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .myui-side, aside").remove()
        val items = mutableListOf<Vod>()
        for (item in doc.select(".module-item")) {
            val link = item.selectFirst("a[href*=/voddetail/]") ?: continue
            val id = vodIdRegex.find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = link.attr("title").trim().ifBlank {
                item.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val cover = resolveUrl(
                img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-original").orEmpty() }, baseUrl)
            val status = item.selectFirst(".module-item-note, .pic-text, span.note")?.text()?.trim() ?: ""
            @Suppress("DEPRECATION")
            items.add(Vod(id, SourceType.EYNY_TV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a:contains(下一页), a.next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".myui-page, .page, .pagination").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    private val playRegex = Regex("/vodplay/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val tabNames = doc.select(".module-tab.module-player-tab .module-tab-item")
            .map { it.text().trim().replace(Regex("\\s*ᴴᴰ\\s*"), "").trim() }
        val lists = doc.select(".module-list.module-player-list")
        val groups = mutableListOf<EpisodeGroup>()
        lists.forEachIndexed { idx, list ->
            val eps = mutableListOf<Episode>()
            var sid = idx + 1
            for (link in list.select("a[href*=/vodplay/]")) {
                val m = playRegex.find(link.attr("href")) ?: continue
                sid = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty()) {
                val name = tabNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "線路 $sid"
                groups.add(EpisodeGroup(name, sid, eps.sortedBy { it.number }))
            }
        }
        @Suppress("DEPRECATION")
        return VodDetail(
            Vod(vodId, SourceType.EYNY_TV, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, groups)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
