package com.gimy.tv.data.update

/**
 * 把 GitHub release 的內文整理成適合直接顯示的純文字。
 *
 * release body 是 markdown，而更新對話框是純 Text，於是 `## 新功能`、`**重點**`
 * 這些語法會原封不動顯示給使用者看——3.1.15 發版後就是這樣（實機截圖可證）。
 *
 * 這裡刻意不做完整的 markdown 解析：更新說明只需要看得懂，不需要排版。
 * 做法是把語法記號拿掉、把標題轉成獨立一行，其餘保持原樣。
 */
internal fun plainChangelog(raw: String): String {
    val lines = raw.replace("\r\n", "\n").split("\n").map { line ->
        var s = line.trim()
        // ## 標題 → 標題（前後留白由下面的空行處理）
        s = s.removePrefix("######").removePrefix("#####").removePrefix("####")
            .removePrefix("###").removePrefix("##").removePrefix("#").trim()
        // 清單符號：- / * / + 開頭
        s = s.removePrefix("- ").removePrefix("* ").removePrefix("+ ")
        // 粗體與斜體：**x** / __x__ / *x* / _x_
        s = s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""__(.+?)__"""), "$1")
            .replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])"""), "$1")
        // 行內程式碼與連結：`x` → x，[標題](網址) → 標題
        s = s.replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        // 引用與水平線
        s = s.removePrefix("> ").trim()
        if (s.matches(Regex("""^([-*_]\s*){3,}$"""))) s = ""
        s
    }
    // 連續空行收成一行，首尾去白
    return lines.joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
