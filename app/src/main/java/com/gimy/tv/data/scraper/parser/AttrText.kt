package com.gimy.tv.data.scraper.parser

import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * 從屬性取「要顯示給使用者看的文字」，多解一次 HTML entity。
 *
 * 站台把片名 **雙重編碼**進屬性裡。實測 2026-09-15 的 gimytv.me/type/2.html：
 *
 * ```
 * aria-label="S&amp;amp;X"     ← 原始 HTML
 * ```
 *
 * Jsoup 解析時已經解過一次（`attr()` 拿到的是 `S&amp;X`），所以畫面上顯示的是
 * 「S&amp;X」而不是片名本身的「S&X」——實機在電視劇分類第一格看到的就是這個。
 * 這不是 Jsoup 沒解碼，是站台編碼了兩次。
 *
 * 沒有 entity 的字串 [Parser.unescapeEntities] 會原樣回傳，所以對沒有雙重編碼的
 * 站台是無作用的，不會把片名裡本來的字元吃掉。
 *
 * `inAttribute = true`：屬性值的 entity 規則比內文嚴格（未加分號的 `&amp` 在屬性裡
 * 不算 entity），照屬性的規則解才不會誤判。
 */
internal fun Element.attrText(name: String): String =
    Parser.unescapeEntities(attr(name), true).trim()
