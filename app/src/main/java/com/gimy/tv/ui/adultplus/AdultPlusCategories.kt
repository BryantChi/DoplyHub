package com.gimy.tv.ui.adultplus

import com.gimy.tv.domain.model.SourceType

/**
 * Static catalogue of adult-plus categories surfaced on the「全部分類」screen.
 *
 * Each entry points at one path on its source. Slugs are verified by curl against
 * the live site (jable.tv/categories/, www.xnxx.com/tags/...) — broken paths just
 * yield empty browse results, never crash the app, but please re-verify whenever
 * editing this list.
 *
 * Why static / not fetched: each scraper would need its own categories endpoint
 * + parser; for the names that matter (12+ Jable categories, 10+ XNXX tags),
 * curating once and shipping is cheaper and more reliable than runtime scraping.
 */
data class AdultPlusCategory(
    val displayName: String,
    val sourceType: SourceType,
    val pathKey: String,
)

object AdultPlusCategories {
    /** All categories. UI groups them by sourceType for the「全部分類」screen. */
    val all: List<AdultPlusCategory> = listOf(
        // ── Jable ── (slugs verified at jable.tv/categories/ index)
        AdultPlusCategory("中文字幕", SourceType.JABLE_TV, "categories/chinese-subtitle"),
        AdultPlusCategory("無碼解放", SourceType.JABLE_TV, "categories/uncensored"),
        AdultPlusCategory("主奴調教", SourceType.JABLE_TV, "categories/bdsm"),
        AdultPlusCategory("直接開啪", SourceType.JABLE_TV, "categories/sex-only"),
        AdultPlusCategory("凌辱快感", SourceType.JABLE_TV, "categories/insult"),
        AdultPlusCategory("制服誘惑", SourceType.JABLE_TV, "categories/uniform"),
        AdultPlusCategory("角色劇情", SourceType.JABLE_TV, "categories/roleplay"),
        AdultPlusCategory("盜攝偷拍", SourceType.JABLE_TV, "categories/private-cam"),
        AdultPlusCategory("男友視角", SourceType.JABLE_TV, "categories/pov"),
        AdultPlusCategory("多P群交", SourceType.JABLE_TV, "categories/groupsex"),
        AdultPlusCategory("絲襪美腿", SourceType.JABLE_TV, "categories/pantyhose"),
        AdultPlusCategory("女同歡愉", SourceType.JABLE_TV, "categories/lesbian"),
        // ── XNXX ── (tags + best/* periods, all 200 OK)
        AdultPlusCategory("亞洲", SourceType.XNXX, "tags/asian"),
        AdultPlusCategory("日本", SourceType.XNXX, "tags/japanese"),
        AdultPlusCategory("中文", SourceType.XNXX, "tags/chinese"),
        AdultPlusCategory("台灣", SourceType.XNXX, "tags/taiwanese"),
        AdultPlusCategory("韓國", SourceType.XNXX, "tags/korean"),
        AdultPlusCategory("素人", SourceType.XNXX, "tags/amateur"),
        AdultPlusCategory("無碼", SourceType.XNXX, "tags/uncensored"),
        AdultPlusCategory("MILF", SourceType.XNXX, "tags/milf"),
        AdultPlusCategory("年輕", SourceType.XNXX, "tags/teen"),
        AdultPlusCategory("大胸", SourceType.XNXX, "tags/big-tits"),
        AdultPlusCategory("本週最佳", SourceType.XNXX, "best/this_week"),
        AdultPlusCategory("本月最佳", SourceType.XNXX, "best/this_month"),
        AdultPlusCategory("今日最佳", SourceType.XNXX, "best/today"),
        // ── 5278 ──
        AdultPlusCategory("成人線上", SourceType.FORUM5278, "forum:23"),
        AdultPlusCategory("線上性感影片", SourceType.FORUM5278, "forum:42"),
    )

    /** All categories for one source, in the order declared above. */
    fun forSource(sourceType: SourceType): List<AdultPlusCategory> =
        all.filter { it.sourceType == sourceType }
}
