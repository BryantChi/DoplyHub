package com.gimy.tv.data.scraper.parser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** 列表／搜尋卡片。 */
internal data class ImapleCard(val cuid: String, val title: String, val coverUrl: String, val status: String)

/** 詳情頁 RSC 的 title 物件。 */
internal data class ImapleTitle(val name: String, val coverUrl: String, val remarks: String)

/** 一條可播的線路。[lineKey] 是播放網址 `?line=` 的值。 */
internal data class ImapleLine(val name: String, val lineKey: String, val episodeNames: List<String>)

/** 詳情頁 JSON-LD 的中繼資料；欄位缺少時給空值，不讓整頁失敗。 */
internal data class ImapleMeta(
    val year: Int = 0,
    val genre: String = "",
    val synopsis: String = "",
    val actors: List<String> = emptyList(),
    val director: String = "",
)

/**
 * eynytv／imaple／123kubo 共用的新平台（Next.js App Router）解析。
 *
 * 只做解析、不碰網路，才能用真實頁面在 JVM 上測。列表與搜尋讀卡片 HTML；線路、集數、
 * 播放網址只存在頁面內嵌的 RSC 資料（self.__next_f.push 片段），只能從那裡讀。兩者分開解析，
 * 站方只改其中一種格式時，另一半照樣能用。
 */
internal object ImapleParser {
    const val PAGE_SIZE = 28

    private const val PUSH_OPEN = "self.__next_f.push([1,\""
    private val LIST_TOTAL = Regex("""共\s*([\d,]+)\s*部""")
    private val SEARCH_TOTAL = Regex("""找到\s*([\d,]+)\s*筆""")
    private val YEAR = Regex("""\d{4}""")

    fun parseCards(doc: Document, baseUrl: String): List<ImapleCard> =
        doc.select("a.stub[href^=/title/]").mapNotNull { a ->
            val cuid = a.attr("href").removePrefix("/title/").substringBefore('?').substringBefore('/')
            if (cuid.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3.stub-title")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.attr("alt")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            ImapleCard(
                cuid = cuid,
                title = title,
                coverUrl = absolutize(img?.attr("src").orEmpty(), baseUrl),
                status = a.selectFirst("p.stub-meta")?.text()?.trim().orEmpty(),
            )
        }.distinctBy { it.cuid }

    fun parseListTotal(doc: Document): Int? = firstNumber(LIST_TOTAL, doc)

    fun parseSearchTotal(doc: Document): Int? = firstNumber(SEARCH_TOTAL, doc)

    private fun firstNumber(re: Regex, doc: Document): Int? =
        re.find(doc.body()?.text().orEmpty())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /**
     * 把所有 RSC 片段解跳脫後依序串起來。
     *
     * 刻意逐字元找結尾引號、不用 `(?:[^"\\]|\\.)*` 這類正則：真實片段約 100KB、上千個跳脫引號，
     * Java 的正則每重複一次就遞迴一層，會在大頁面上 StackOverflowError。
     * 片段是 JSON 相容的字串字面值（站方以 JSON.stringify 產生），解跳脫交給 JSONTokener。
     */
    fun extractRsc(html: String): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val open = html.indexOf(PUSH_OPEN, from)
            if (open < 0) break
            val start = open + PUSH_OPEN.length
            val end = closingQuote(html, start)
            if (end < 0) break
            runCatching { JSONTokener(html.substring(start - 1, end + 1)).nextValue() as String }
                .onSuccess { out.append(it) }
            from = end + 1
        }
        return out.toString()
    }

    private fun closingQuote(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    fun parseTitle(rsc: String, baseUrl: String): ImapleTitle? {
        // RSC 裡 "title" 這個 key 不只一處（例如頁面 metadata），真正的片子物件以 "id" 開頭。
        val obj = jsonAfter(rsc, "title", '{') { it.startsWith("{\"id\"") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val name = obj.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return ImapleTitle(name, absolutize(obj.optString("pic"), baseUrl), obj.optString("remarks").trim())
    }

    fun parseLines(rsc: String): List<ImapleLine> {
        val arr = jsonAfter(rsc, "lines", '[')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 站方標記已死的線路不給選；需要站方代理才能播的線路行為未驗證，先不提供。
            if (o.optBoolean("knownDead") || o.optBoolean("needsProxy")) return@mapNotNull null
            val sourceKey = o.optString("sourceKey").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val from = o.optString("from")
            val eps = o.optJSONArray("episodes") ?: return@mapNotNull null
            val names = (0 until eps.length()).map { j -> eps.optJSONObject(j)?.optString("name").orEmpty() }
            if (names.isEmpty()) return@mapNotNull null
            val label = o.optString("sourceLabel").trim().ifEmpty { from.substringBefore('·').trim() }
            ImapleLine(label, lineKeyOf(from, sourceKey), names)
        }
    }

    /** `?line=` 的值：sourceKey 加上 from 裡「·」（U+00B7）後的格式代碼，例如 cj-ffzyapi-com.ffm3u8。 */
    fun lineKeyOf(from: String, sourceKey: String): String {
        val format = from.substringAfter('·', "").trim()
        return if (format.isEmpty()) sourceKey else "$sourceKey.$format"
    }

    /**
     * 指定線路、第 [episodeNumber] 集（從 1 起）的 m3u8；沒有就回 null。
     *
     * 只有網址 `?line=` 指定的那條線路會帶全部集數的 url，其他線路只帶鄰近幾集、其餘是 null，
     * 所以一定要用「該線路、該集」的播放頁請求，不能拿別條線路的頁面來找。
     */
    fun parseStreamUrl(rsc: String, lineKey: String, episodeNumber: Int): String? {
        val groups = jsonAfter(rsc, "playGroups", '[')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return null
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (lineKeyOf(g.optString("from"), g.optString("sourceKey")) != lineKey) continue
            val ep = g.optJSONArray("episodes")?.optJSONObject(episodeNumber - 1) ?: return null
            return ep.optString("url").trim().takeIf { it.startsWith("http") }
        }
        return null
    }

    fun parseMeta(doc: Document): ImapleMeta {
        val fallbackSynopsis = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        val item = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { JSONTokener(it.data()).nextValue() }.getOrNull() }
            .flatMap { v ->
                when (v) {
                    is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.asSequence()
                    is JSONObject -> sequenceOf(v)
                    else -> emptySequence()
                }
            }
            .firstOrNull { it.has("datePublished") || it.has("actor") }
            ?: return ImapleMeta(synopsis = fallbackSynopsis)
        return ImapleMeta(
            year = YEAR.find(item.optString("datePublished"))?.value?.toIntOrNull() ?: 0,
            genre = strings(item.opt("genre")).joinToString(" / "),
            synopsis = item.optString("description").trim().ifEmpty { fallbackSynopsis },
            actors = personNames(item.opt("actor")),
            director = personNames(item.opt("director")).joinToString(" / "),
        )
    }

    private fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        else -> emptyList()
    }

    private fun personNames(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            (v.optJSONObject(i)?.optString("name") ?: v.optString(i)).trim().takeIf { it.isNotEmpty() }
        }
        is JSONObject -> listOfNotNull(v.optString("name").trim().takeIf { it.isNotEmpty() })
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /**
     * 找出 `"key":` 後面緊接著、以 [open] 開頭的 JSON 值，回傳完整原始字串。
     *
     * 逐字元掃描並追蹤「是否在字串內」：片名或線路名裡的 ] } 不能被當成結構符號，否則會在
     * 錯的位置截斷。同一個 key 可能出現多次，[accept] 用來挑出正確的那一個。
     */
    internal fun jsonAfter(text: String, key: String, open: Char, accept: (String) -> Boolean = { true }): String? {
        val needle = "\"$key\":"
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            val start = at + needle.length
            if (start < text.length && text[start] == open) {
                val end = matchingEnd(text, start)
                if (end > start) {
                    val value = text.substring(start, end + 1)
                    if (accept(value)) return value
                }
            }
            from = start
        }
    }

    private fun matchingEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun absolutize(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl.trimEnd('/') + url
        else -> url
    }
}
