package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * Jable detail pages end with a 24-card recommendation grid built from the same template as
 * the list page — so `h6.title` matches 24 sibling videos, and `selectFirst` on it returns
 * whichever one the DOM happens to put first, not the video being viewed. That is how watch
 * history ended up showing a completely different title.
 *
 * Structure captured from a real detail page on 2026-09-10: no h1 at all, og:title and the
 * info-box h4 both hold the real title, three h4s exist in total.
 */
class JableDetailTitleTest {

    private fun page(ogTitle: String?, infoH4: String?, recommended: List<String>) = Jsoup.parse(
        buildString {
            append("<html><head>")
            if (ogTitle != null) append("""<meta property="og:title" content="$ogTitle">""")
            append("</head><body>")
            append("""<div class="info-header">""")
            if (infoH4 != null) append("<h4>$infoH4</h4>")
            append("</div>")
            append("""<section class="recommend">""")
            recommended.forEach {
                append("""<h6 class="title"><a href="/videos/other/">$it</a></h6>""")
            }
            append("<h4>推薦影片</h4>")
            append("</section></body></html>")
        }
    )

    @Test fun `prefers og-title over anything in the page body`() {
        val doc = page("MNGS-064 Real Title", "MNGS-064 Real Title", listOf("MIMK-253 Other"))
        assertThat(jableDetailTitle(doc)).isEqualTo("MNGS-064 Real Title")
    }

    /**
     * The actual regression: a recommendation card sits before everything useful in the DOM.
     * Picking it is what produced the wrong history entry.
     */
    @Test fun `never picks a recommendation card title`() {
        val doc = page("MNGS-064 Real Title", null, listOf("MIMK-253 Other", "ABC-001 Another"))
        val title = jableDetailTitle(doc)
        assertThat(title).doesNotContain("MIMK-253")
        assertThat(title).doesNotContain("ABC-001")
    }

    @Test fun `falls back to the info-box heading when og-title is absent`() {
        val doc = page(null, "MNGS-064 Real Title", listOf("MIMK-253 Other"))
        assertThat(jableDetailTitle(doc)).isEqualTo("MNGS-064 Real Title")
    }

    /** The fallback must not grab the recommendation section's own h4 heading either. */
    @Test fun `fallback ignores headings outside the info box`() {
        val doc = page(null, null, listOf("MIMK-253 Other"))
        assertThat(jableDetailTitle(doc)).isNotEqualTo("推薦影片")
        assertThat(jableDetailTitle(doc)).doesNotContain("MIMK-253")
    }

    @Test fun `blank og-title does not win over a usable heading`() {
        val doc = page("   ", "MNGS-064 Real Title", listOf("MIMK-253 Other"))
        assertThat(jableDetailTitle(doc)).isEqualTo("MNGS-064 Real Title")
    }
}
