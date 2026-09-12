package com.gimy.tv.domain.util

import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.EpisodeStatus

/**
 * Parses a scraper-emitted status string into a structured [EpisodeStatus].
 * Output is canonical via [EpisodeStatus.display], plus a structured variant so
 * downstream layers (Normalizer, Repository, UI) can make decisions without
 * re-parsing the string.
 *
 * Result for InProgress is always tagged [Confidence.SiteDeclared] — the caller
 * must re-tag if the number was derived from a different source (e.g. parsed
 * episode list, cross-site max).
 *
 * Detection order matters; tests in EpisodeStatusParserTest pin the contract.
 */
fun parseEpisodeStatus(raw: String): EpisodeStatus {
    val s = raw.trim()
    if (s.isEmpty()) return EpisodeStatus.Empty

    // Finished — check before generic "更新" because some sites emit
    // "全X集 更新中" oddities; "N集全" / "全N集" / "完結 N 集" / "已完結 N 集".
    Regex("全\\s*(\\d+)\\s*集").find(s)?.let { m ->
        m.groupValues[1].toEpisodeCountOrNull()?.let { return EpisodeStatus.Finished(it) }
    }
    Regex("(\\d+)\\s*集\\s*全").find(s)?.let { m ->
        m.groupValues[1].toEpisodeCountOrNull()?.let { return EpisodeStatus.Finished(it) }
    }
    Regex("完結.*?(\\d+).*?集").find(s)?.let { m ->
        m.groupValues[1].toEpisodeCountOrNull()?.let { return EpisodeStatus.Finished(it) }
    }

    // In-progress — "更新至第 N 集" / "更新 N 集" / "更新到 N 集"
    Regex("更新.*?(\\d+).*?集").find(s)?.let { m ->
        m.groupValues[1].toEpisodeCountOrNull()
            ?.let { return EpisodeStatus.InProgress(it, Confidence.SiteDeclared) }
    }
    // Movieffm-style "更新 N" without 集 suffix
    Regex("更新\\D*(\\d+)\\s*$").find(s)?.let { m ->
        m.groupValues[1].toEpisodeCountOrNull()
            ?.let { return EpisodeStatus.InProgress(it, Confidence.SiteDeclared) }
    }

    // Quality / language tags that movieffm uses for movies.
    if (s.equals("HD", ignoreCase = true) || s == "中字" || s == "藍光" || s == "4K") {
        return EpisodeStatus.Movie(s)
    }

    // Pass-through for unparseable but non-empty status ("預告" / "完結" without count / etc.)
    return EpisodeStatus.Raw(s)
}

/**
 * 把擷取到的數字當集數看待；不是合理集數就回 null。
 *
 * 為什麼不用 toInt()：regex 的 `(\d+)` 長度無上限，站方的狀態字串偶爾會混進日期而不是集數
 * ——實際遇過 movieffm 的綜藝分類出現 "202520250915"，`toInt()` 直接拋 NumberFormatException。
 * 這個 parser 是所有來源共用的，而呼叫端多半把整批 catch 掉，於是**一筆髒資料就讓整個分類
 * 整組消失**，畫面上完全看不出發生過什麼事。
 *
 * 上界取 9999：真實影集不會有上萬集，超過的幾乎必然是年份、日期或 id 被誤配。回 null 之後
 * 呼叫端會往下試其他規則，最終落到 [EpisodeStatus.Raw] 原樣顯示，比硬湊一個荒謬的集數好。
 */
private fun String.toEpisodeCountOrNull(): Int? =
    toIntOrNull()?.takeIf { it in 1..9999 }
