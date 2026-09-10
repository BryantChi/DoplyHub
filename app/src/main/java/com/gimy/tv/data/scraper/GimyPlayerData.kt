package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.extractPlayerJson
import com.gimy.tv.domain.model.PlayerData
import org.json.JSONObject

/**
 * Shared play-page decoding for the Gimy mirrors (GIMYTV / GIMYMAX), which emit the same
 * blob shape. Kept out of the parser package because JSONObject and Base64 are Android
 * framework classes — the pure locating step lives in [extractPlayerJson] so it stays
 * unit-testable on the JVM.
 */
internal fun parseGimyPlayerData(html: String): PlayerData {
    val json = JSONObject(extractPlayerJson(html))
    val encrypt = json.optInt("encrypt", 0)
    val raw = json.optString("url", "")

    val url = when (encrypt) {
        1 -> String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
        2 -> String(
            android.util.Base64.decode(
                String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT)),
                android.util.Base64.DEFAULT,
            )
        )
        else -> raw
    }
    if (url.isBlank()) throw ScraperException("Empty stream URL")
    return PlayerData(url, encrypt, json.optString("from", ""))
}
