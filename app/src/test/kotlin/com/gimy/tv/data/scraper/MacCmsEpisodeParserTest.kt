package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.MacCmsEpisodeParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class MacCmsEpisodeParserTest {

    private val playlistMobileHtml = """
        <div class="playlist-mobile playlist layout-box clearfix">
          <li><div class="gico 1080zyk">卧龍雲</div></li>
          <ul id="con_playlist_1"><li><a href="/vodplay/235668-1-1.html">第1集</a>
            <a href="/vodplay/235668-1-2.html">第2集</a></li></ul>
        </div>
        <div class="playlist-mobile playlist layout-box clearfix">
          <li><div class="gico">索尼雲</div></li>
          <ul id="con_playlist_2"><li><a href="/vodplay/235668-2-1.html">第1集</a></li></ul>
        </div>
    """.trimIndent()

    @Test fun `playlist-mobile branch keeps real line names not 線路 N`() {
        val doc = Jsoup.parse(playlistMobileHtml, "https://gimy.tw")
        val groups = MacCmsEpisodeParser.parse(doc, playUrlPath = "/vodplay")
        assertThat(groups.map { it.sourceName }).containsExactly("卧龍雲", "索尼雲")
        val wolong = groups.first { it.sourceName == "卧龍雲" }
        assertThat(wolong.episodes).hasSize(2)
        assertThat(wolong.episodes[0].playUrl).isEqualTo("/vodplay/235668-1-1.html")
    }

    @Test fun `data-toggle tab branch still works`() {
        val tabHtml = """
            <a href="#playlist1" data-toggle="tab">高清</a>
            <div id="playlist1"><ul><li><a href="/vodplay/5-1-1.html">第1集</a></li></ul></div>
        """.trimIndent()
        val groups = MacCmsEpisodeParser.parse(Jsoup.parse(tabHtml, "https://x"), "/vodplay")
        assertThat(groups).hasSize(1)
        assertThat(groups[0].sourceName).isEqualTo("高清")
    }
}
