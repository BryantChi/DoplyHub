package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Gimy 系列（gimytv.me / gimyai.tw / gitube.tv）的搜尋端點。
 *
 * ## 為什麼不走搜尋頁
 *
 * 站方在 Cloudflare 掛的 WAF 規則是看 **query string 有沒有 `wd` 參數**，實測（2026-09-11）：
 *
 * | 網址 | 結果 |
 * |---|---|
 * | `/type/20.html` | 200 |
 * | `/type/20.html?wd=恐怖` | 403 Managed Challenge |
 * | `/api.php/provide/vod/?ac=list` | 200 |
 * | `/api.php/provide/vod/?ac=list&wd=恐怖` | 403 |
 * | `POST /index.php/ajax/suggest`（wd 在 body） | **200，乾淨 JSON** |
 *
 * 也就是說列表、詳情、播放全都通，只有帶關鍵字的請求被擋。原本的做法是背景開
 * WebView 解 cf_clearance，但 Managed Challenge 需要通過瀏覽器指紋檢查，在電視盒與
 * 模擬器上都不可靠——解不出來時整組 Gimy 就從搜尋結果裡靜默消失。
 *
 * WAF 只檢查 query string，把關鍵字移到 POST body 就完全不觸發，不必再依賴 WebView。
 *
 * ## 端點限制
 *
 * - 沒有分頁：`page` / `pg` 參數都被忽略，永遠回第一頁；但 `limit` 有效（實測到 100）。
 *   所以第一頁一次多要一些，第二頁之後直接回空。
 * - 回傳欄位只有 `id` / `name` / `en` / `pic`，沒有年份、分類、更新集數。搜尋結果的卡片
 *   本來就以片名與封面為主，其餘進詳情頁自然會補齊。
 */
internal const val GIMY_SUGGEST_PATH = "/index.php/ajax/suggest"

/** 第一頁一次要的筆數。聚合搜尋還會與其他來源合併去重，要太多只是浪費頻寬。 */
internal const val GIMY_SUGGEST_LIMIT = 40

/**
 * 組出 POST 的 form body。關鍵字**必須**放在這裡而不是 query string，否則會觸發 WAF。
 *
 * `mid=1` 是 MacCMS 的模型 id（1 = 影片）。
 */
internal fun gimySuggestForm(keyword: String, limit: Int = GIMY_SUGGEST_LIMIT): String {
    val wd = URLEncoder.encode(keyword, "UTF-8")
    return "mid=1&wd=$wd&limit=$limit"
}

/**
 * 解析 suggest 的回應。
 *
 * 任何解析失敗都回空清單而不是拋例外：聚合搜尋同時打八個來源，其中一個回了非預期的
 * 內容（例如站方臨時擋下來回了 HTML 挑戰頁）不該讓整次搜尋掛掉。
 */
internal fun parseGimySuggest(json: String, sourceType: SourceType): List<Vod> = runCatching {
    val list = JSONObject(json).optJSONArray("list") ?: return emptyList()
    buildList {
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L).takeIf { it > 0L } ?: continue
            val title = item.optString("name").trim()
            if (title.isBlank()) continue
            @Suppress("DEPRECATION")
            add(
                Vod(
                    id = id,
                    sourceType = sourceType,
                    title = title,
                    coverUrl = item.optString("pic").trim(),
                    category = "",
                    year = 0,
                    status = "",
                ),
            )
        }
    }
}.getOrDefault(emptyList())
