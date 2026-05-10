package com.gimy.tv.domain.util

/**
 * Normalize the wide variety of status strings scrapers emit into a small set
 * of consistent display forms. Sites are inconsistent — gimytv emits both
 * "更新至第33集" and "38集全"; movieffm emits "更新26" / "HD" / "中字"; MacCMS sites
 * emit "完結" / "更新N集" / "N集全". Without this, the home/search grid shows a
 * jumble of formats which the user reads as "the count is wrong" even when it
 * is actually correct.
 *
 * Output forms (ordered by specificity):
 *   - "更新至 N 集"    — series in progress (any "更新" + digits)
 *   - "完結 · N 集"    — finished series (any "全/全部/完結" + digits, or "N集全")
 *   - "N 集"           — bare digit + 集 (no progress / completion marker)
 *   - "HD" / "中字" / "完結" / "預告" / etc — passthrough (no episode count)
 *   - blank input      — blank output
 */
fun prettifyVodStatus(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return s

    // "N集全" / "全N集" / "全 N 集" — finished series. Check before generic
    // "更新" because some sites emit "全X集 更新中" oddities.
    Regex("全\\s*(\\d+)\\s*集").find(s)?.let {
        return "完結 · ${it.groupValues[1]} 集"
    }
    Regex("(\\d+)\\s*集\\s*全").find(s)?.let {
        return "完結 · ${it.groupValues[1]} 集"
    }
    // "完結 N 集" / "已完結 N 集"
    Regex("完結.*?(\\d+).*?集").find(s)?.let {
        return "完結 · ${it.groupValues[1]} 集"
    }

    // "更新至第 N 集" / "更新 N 集" / "更新到 N 集" — in-progress
    Regex("更新.*?(\\d+).*?集").find(s)?.let {
        return "更新至 ${it.groupValues[1]} 集"
    }
    // "更新 N" (no 集 suffix) — movieffm style
    Regex("更新\\D*(\\d+)\\s*$").find(s)?.let {
        return "更新至 ${it.groupValues[1]} 集"
    }

    // Bare "第 N 集" / "N 集"
    Regex("(?:第)?\\s*(\\d+)\\s*集").find(s)?.let {
        // Only convert if the entire status is essentially the count (avoid
        // mangling "預告 第3集" or "OAD N集" labels).
        if (it.value.length >= s.length - 1) return "${it.groupValues[1]} 集"
    }

    // Passthrough — "HD" / "中字" / "完結" / "預告" / "全集" / etc.
    return s
}
