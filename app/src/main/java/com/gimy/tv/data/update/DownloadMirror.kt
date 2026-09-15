package com.gimy.tv.data.update

/**
 * APK 的下載鏡像。
 *
 * GitHub 的 release asset 在台灣慢得離譜——2026-09-15 實測同一支 5.7MB 的 APK：
 *
 * | 來源 | 速度 | 耗時 |
 * |---|---|---|
 * | github.com/.../releases/download | 89 KB/s | 40 秒還沒下完 |
 * | cdn.jsdelivr.net | 1,013 KB/s | 5.9 秒 |
 *
 * 差 11 倍，而且慢到會踩中舊版那個 20 秒的 callTimeout（3.1.14 的使用者因此完全
 * 更新不了，只能手動安裝）。所以優先走 CDN，連不上再退回 GitHub。
 *
 * jsDelivr 抓的是 **repo 裡的檔案**（發版時 APK 會一起進 `mov_app/<tag>/`），
 * 不是 release asset，所以路徑得自己組。實測兩邊的 MD5 一致。
 */
internal object DownloadMirror {

    /**
     * 把 GitHub 的下載網址換成 CDN 的。
     *
     * 組不出來（tag 或 repo 是空的、檔名不符發版慣例）就回 null，呼叫端照樣用原本的網址——
     * 這條路徑是加速用的，不該因為格式沒對上就讓整個更新失效。
     */
    fun cdnUrlFor(repo: String, tag: String, apkFileName: String): String? {
        if (repo.isBlank() || tag.isBlank() || apkFileName.isBlank()) return null
        if (!apkFileName.endsWith(".apk", ignoreCase = true)) return null
        // repo 必須是 owner/name 的形狀，否則組出來的網址只會 404
        if (repo.count { it == '/' } != 1) return null
        return "https://cdn.jsdelivr.net/gh/$repo@$tag/mov_app/$tag/$apkFileName"
    }
}
