package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MacCms sites do not agree on a search endpoint. Most serve the classic
 * /vodsearch/-------------.html?wd=, but momovod and 123kubo use /search.html?wd= — and
 * those two also rename their list/detail paths (/type, /vod), so the divergence is
 * consistent with how they differ elsewhere.
 *
 * The shared builder hardcoded the classic form, so search on those two answered 404 and
 * they silently contributed nothing to aggregated results.
 */
class MacCmsSearchUrlTest {

    private val base = "https://imaple.tv"

    @Test fun `classic style uses the vodsearch path`() {
        val url = macCmsSearchUrl(base, "流星", MacCmsSearchStyle.VODSEARCH)
        assertThat(url).isEqualTo("https://imaple.tv/vodsearch/-------------.html?wd=%E6%B5%81%E6%98%9F")
    }

    @Test fun `search-html style uses the short path`() {
        val url = macCmsSearchUrl("https://momovod.app", "流星", MacCmsSearchStyle.SEARCH_HTML)
        assertThat(url).isEqualTo("https://momovod.app/search.html?wd=%E6%B5%81%E6%98%9F")
    }

    @Test fun `keywords are url encoded`() {
        val url = macCmsSearchUrl(base, "a b&c", MacCmsSearchStyle.VODSEARCH)
        assertThat(url).contains("wd=a+b%26c")
        assertThat(url).doesNotContain(" ")
    }

    @Test fun `ascii keywords survive unchanged`() {
        assertThat(macCmsSearchUrl("https://x.tv", "abc", MacCmsSearchStyle.SEARCH_HTML))
            .isEqualTo("https://x.tv/search.html?wd=abc")
    }
}
