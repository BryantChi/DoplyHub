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
        // Jable tags — popular themes, slugs verified at jable.tv/tags/
        AdultPlusCategory("巨乳", SourceType.JABLE_TV, "tags/big-tits"),
        AdultPlusCategory("中出", SourceType.JABLE_TV, "tags/creampie"),
        AdultPlusCategory("口交", SourceType.JABLE_TV, "tags/blowjob"),
        AdultPlusCategory("3P/4P", SourceType.JABLE_TV, "tags/3p"),
        AdultPlusCategory("情侶", SourceType.JABLE_TV, "tags/couple"),
        AdultPlusCategory("旗袍", SourceType.JABLE_TV, "tags/cheongsam"),
        AdultPlusCategory("兔女郎", SourceType.JABLE_TV, "tags/bunny-girl"),
        AdultPlusCategory("浴池", SourceType.JABLE_TV, "tags/bathing-place"),
        AdultPlusCategory("綁縛", SourceType.JABLE_TV, "tags/bondage"),
        AdultPlusCategory("美腿", SourceType.JABLE_TV, "tags/beautiful-leg"),
        AdultPlusCategory("美臀", SourceType.JABLE_TV, "tags/beautiful-butt"),
        AdultPlusCategory("黑絲", SourceType.JABLE_TV, "tags/black-pantyhose"),
        AdultPlusCategory("不倫", SourceType.JABLE_TV, "tags/affair"),
        AdultPlusCategory("黑人", SourceType.JABLE_TV, "tags/black"),
        AdultPlusCategory("痴漢", SourceType.JABLE_TV, "tags/chikan"),
        AdultPlusCategory("母乳", SourceType.JABLE_TV, "tags/breast-milk"),
        AdultPlusCategory("青梅竹馬", SourceType.JABLE_TV, "tags/childhood"),
        AdultPlusCategory("風俗", SourceType.JABLE_TV, "tags/club-hostess-and-sex-worker"),
        AdultPlusCategory("校園", SourceType.JABLE_TV, "tags/school"),
        AdultPlusCategory("護士", SourceType.JABLE_TV, "tags/nurse"),
        // ── XNXX ── /tags/{x} 2026-10 起 301 到 /search/{x}（且 /tags/{x}/N 會轉回第一頁），
        // 所以直接寫 search；"best" 由 xnxxListPath 展開成月榜（本週／今日榜已不存在）。
        AdultPlusCategory("亞洲", SourceType.XNXX, "search/asian"),
        AdultPlusCategory("日本", SourceType.XNXX, "search/japanese"),
        AdultPlusCategory("中文", SourceType.XNXX, "search/chinese"),
        AdultPlusCategory("台灣", SourceType.XNXX, "search/taiwanese"),
        AdultPlusCategory("韓國", SourceType.XNXX, "search/korean"),
        AdultPlusCategory("素人", SourceType.XNXX, "search/amateur"),
        AdultPlusCategory("無碼", SourceType.XNXX, "search/uncensored"),
        AdultPlusCategory("MILF", SourceType.XNXX, "search/milf"),
        AdultPlusCategory("年輕", SourceType.XNXX, "search/teen"),
        AdultPlusCategory("大胸", SourceType.XNXX, "search/big-tits"),
        AdultPlusCategory("本月最佳", SourceType.XNXX, "best"),
        AdultPlusCategory("Cosplay", SourceType.XNXX, "search/cosplay"),
        AdultPlusCategory("後庭", SourceType.XNXX, "search/anal"),
        AdultPlusCategory("高潮", SourceType.XNXX, "search/orgasm"),
        AdultPlusCategory("蕾絲邊", SourceType.XNXX, "search/lesbian"),
        AdultPlusCategory("3P", SourceType.XNXX, "search/threesome"),
        AdultPlusCategory("中出", SourceType.XNXX, "search/creampie"),
        AdultPlusCategory("口交", SourceType.XNXX, "search/blowjob"),
        AdultPlusCategory("美臀", SourceType.XNXX, "search/big-ass"),
        AdultPlusCategory("繼母", SourceType.XNXX, "search/step-mom"),
        AdultPlusCategory("繼妹", SourceType.XNXX, "search/step-sister"),
        // ── 5278 ──
        AdultPlusCategory("成人線上", SourceType.FORUM5278, "forum:23"),
        AdultPlusCategory("線上性感影片", SourceType.FORUM5278, "forum:42"),
        AdultPlusCategory("激情性愛區", SourceType.FORUM5278, "forum:4"),
        AdultPlusCategory("西洋辣妹區", SourceType.FORUM5278, "forum:18"),
        AdultPlusCategory("絲襪美腿區", SourceType.FORUM5278, "forum:19"),
        AdultPlusCategory("魚訊分享區", SourceType.FORUM5278, "forum:121"),
    )

    /** All categories for one source, in the order declared above. */
    fun forSource(sourceType: SourceType): List<AdultPlusCategory> =
        all.filter { it.sourceType == sourceType }
}
