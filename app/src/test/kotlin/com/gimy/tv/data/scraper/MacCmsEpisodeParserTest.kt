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

    /**
     * 2026-10 的 gimy.tw 詳情頁（取自線上，集數刪減）：線路名改成 `<i class="gico"></i><span>名稱</span>`，
     * 桌面 tab 的 href 是 #con_playlist_N 而不是 #playlistN。前兩個分支都對不上時會掉到保底分支，
     * 線路名變「線路 N」（排序跟著失效），而且桌面＋手機兩份清單被收在一起，每一集出現兩次。
     */
    private val gimyTw202610Html = """
        <ul class="nav nav-tabs hidden-xs" id="playTab">
          <li class="hidden-xs "><a class="gico jsm3u8" href="#con_playlist_4" data-toggle="tab">極速雲</a></li>
          <li class="hidden-xs "><a class="gico 1080zyk" href="#con_playlist_3" data-toggle="tab">優質雲</a></li>
        </ul>
        <div class="layout-box clearfix m-0 hidden-xs"><div class="playlist">
          <ul class="clearfix fade " id="con_playlist_4"><li><a href="/vodplay/157714-4-1.html">第01集</a></li><li><a href="/vodplay/157714-4-2.html">第02集</a></li></ul>
          <ul class="clearfix fade " id="con_playlist_3"><li><a href="/vodplay/157714-3-1.html">第01集</a></li></ul>
        </div></div>
        <div class="playlist-mobile playlist layout-box clearfix hidden-sm hidden-md hidden-lg"> <i class="gico jsm3u8" style="display:inline-block;"></i> <span style="font-weight:700;">極速雲</span>
          <ul class="clearfix fade in active" id="con_playlist_4"><li><a href="/vodplay/157714-4-1.html">第01集</a></li><li><a href="/vodplay/157714-4-2.html">第02集</a></li></ul>
        </div>
        <div class="playlist-mobile playlist layout-box clearfix hidden-sm hidden-md hidden-lg"> <i class="gico 1080zyk" style="display:inline-block;"></i> <span style="font-weight:700;">優質雲</span>
          <ul class="clearfix fade in active" id="con_playlist_3"><li><a href="/vodplay/157714-3-1.html">第01集</a></li></ul>
        </div>
    """.trimIndent()

    @Test fun `gimy tw 2026-10 layout keeps real line names`() {
        val groups = MacCmsEpisodeParser.parse(Jsoup.parse(gimyTw202610Html, "https://gimy.tw"), "/vodplay")
        assertThat(groups.map { it.sourceName }).containsExactly("極速雲", "優質雲").inOrder()
    }

    @Test fun `gimy tw 2026-10 layout lists each episode once`() {
        val groups = MacCmsEpisodeParser.parse(Jsoup.parse(gimyTw202610Html, "https://gimy.tw"), "/vodplay")
        val jisu = groups.first { it.sourceId == 4 }
        assertThat(jisu.episodes.map { it.number }).containsExactly(1, 2).inOrder()
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
