package com.gimy.tv.data.repair

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The repair rewrites titles inside the user's own favourites and history, so the bar for
 * overwriting is high: a failed fetch must leave the existing row untouched rather than
 * replacing a wrong-but-readable title with "Unknown" or an empty string.
 */
class TitleRepairTest {

    @Test fun `replaces a stale title with a freshly parsed one`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "MNGS-064 right")).isTrue()
    }

    @Test fun `a failed fetch never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = null)).isFalse()
    }

    @Test fun `a blank result never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "   ")).isFalse()
    }

    /** "Unknown" is the parser's own giving-up value — writing it back loses information. */
    @Test fun `the parser fallback value never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "Unknown")).isFalse()
    }

    @Test fun `an unchanged title is not rewritten`() {
        assertThat(shouldReplaceTitle(old = "MNGS-064 right", fetched = "MNGS-064 right")).isFalse()
    }

    /** Whitespace-only differences are not worth a database write. */
    @Test fun `whitespace-only differences are ignored`() {
        assertThat(shouldReplaceTitle(old = "MNGS-064 right", fetched = "  MNGS-064 right  ")).isFalse()
    }
}
