package com.gimy.tv.domain.util

import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.EpisodeStatus

/**
 * Parses a scraper-emitted status string into a structured [EpisodeStatus].
 * Replaces the v2.x [prettifyVodStatus] regex pipeline; output is the SAME
 * canonical strings via [EpisodeStatus.display], plus a structured variant so
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
    Regex("全\\s*(\\d+)\\s*集").find(s)?.let {
        return EpisodeStatus.Finished(it.groupValues[1].toInt())
    }
    Regex("(\\d+)\\s*集\\s*全").find(s)?.let {
        return EpisodeStatus.Finished(it.groupValues[1].toInt())
    }
    Regex("完結.*?(\\d+).*?集").find(s)?.let {
        return EpisodeStatus.Finished(it.groupValues[1].toInt())
    }

    // In-progress — "更新至第 N 集" / "更新 N 集" / "更新到 N 集"
    Regex("更新.*?(\\d+).*?集").find(s)?.let {
        return EpisodeStatus.InProgress(it.groupValues[1].toInt(), Confidence.SiteDeclared)
    }
    // Movieffm-style "更新 N" without 集 suffix
    Regex("更新\\D*(\\d+)\\s*$").find(s)?.let {
        return EpisodeStatus.InProgress(it.groupValues[1].toInt(), Confidence.SiteDeclared)
    }

    // Bare "第 N 集" / "N 集" — only when status is essentially the count alone
    // (avoid mangling "預告 第3集" / "OAD 5集" labels that should stay Raw).
    Regex("(?:第)?\\s*(\\d+)\\s*集").find(s)?.let { match ->
        if (match.value.length >= s.length - 1) {
            return EpisodeStatus.InProgress(match.groupValues[1].toInt(), Confidence.SiteDeclared)
        }
    }

    // Quality / language tags that movieffm uses for movies.
    if (s.equals("HD", ignoreCase = true) || s == "中字" || s == "藍光" || s == "4K") {
        return EpisodeStatus.Movie(s)
    }

    // Pass-through for unparseable but non-empty status ("預告" / "完結" without count / etc.)
    return EpisodeStatus.Raw(s)
}
