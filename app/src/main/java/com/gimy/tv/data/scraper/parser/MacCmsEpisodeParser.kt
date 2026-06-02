package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import org.jsoup.nodes.Document

object MacCmsEpisodeParser {

    fun parse(doc: Document, playUrlPath: String): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        val tabIdRegex = Regex("#playlist(\\d+)")
        val playLinkRegex = Regex("$playUrlPath/\\d+-(\\d+)-(\\d+)\\.html")

        // Branch 1: data-toggle=tab + #playlistN (original base logic, verbatim)
        val tabs = doc.select("a[data-toggle=tab][href^=#playlist]")
        for (tab in tabs) {
            val tabId = tabIdRegex.find(tab.attr("href"))?.groupValues?.get(1) ?: continue
            val name = tab.text().trim()
                .replace(Regex("\\s*ᴴᴰ\\s*"), "")
                .replace(Regex("\\s+"), "")
            if (name.isBlank()) continue
            val container = doc.getElementById("playlist$tabId") ?: continue
            val episodes = mutableListOf<Episode>()
            var sourceId = 0
            for (link in container.select("a[href*=$playUrlPath/]")) {
                val m = playLinkRegex.find(link.attr("href")) ?: continue
                sourceId = m.groupValues[1].toIntOrNull() ?: continue
                val epNum = m.groupValues[2].toIntOrNull() ?: continue
                episodes.add(Episode(epNum, link.text().trim(), link.attr("href")))
            }
            if (episodes.isNotEmpty()) {
                groups.add(EpisodeGroup(name, sourceId, episodes.sortedBy { it.number }))
            }
        }

        // Branch 2: playlist-mobile + div.gico + ul#con_playlist_ (gimy.tw redesign)
        if (groups.isEmpty()) {
            for (container in doc.select("div.playlist-mobile.playlist")) {
                val name = container.selectFirst("div.gico")?.text()?.trim()
                    ?.replace(Regex("\\s*ᴴᴰ\\s*"), "")?.trim() ?: continue
                val ul = container.selectFirst("ul[id^=con_playlist_]") ?: continue
                val episodes = mutableListOf<Episode>()
                var sourceId = 0
                for (link in ul.select("a[href*=$playUrlPath/]")) {
                    val m = playLinkRegex.find(link.attr("href")) ?: continue
                    sourceId = m.groupValues[1].toIntOrNull() ?: continue
                    val epNum = m.groupValues[2].toIntOrNull() ?: continue
                    episodes.add(Episode(epNum, link.text().trim(), link.attr("href")))
                }
                if (episodes.isNotEmpty() && name.isNotBlank()) {
                    groups.add(EpisodeGroup(name, sourceId, episodes.sortedBy { it.number }))
                }
            }
        }

        // Branch 3: Fallback — bucket all play links by sourceId, generic "線路 N"
        // (original base fallback logic, verbatim)
        if (groups.isEmpty()) {
            val bySource = mutableMapOf<Int, MutableList<Episode>>()
            for (link in doc.select("a[href*=$playUrlPath/]")) {
                val m = playLinkRegex.find(link.attr("href")) ?: continue
                val sId = m.groupValues[1].toIntOrNull() ?: continue
                val ep = m.groupValues[2].toIntOrNull() ?: continue
                bySource.getOrPut(sId) { mutableListOf() }
                    .add(Episode(ep, link.text().trim(), link.attr("href")))
            }
            for ((sId, eps) in bySource) {
                groups.add(EpisodeGroup("線路 $sId", sId, eps.sortedBy { it.number }))
            }
        }

        return groups
    }
}
