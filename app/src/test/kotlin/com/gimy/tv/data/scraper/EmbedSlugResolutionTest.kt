package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Jable/XNXX detail URLs are slugs, but Vod.id must be a Long, so the id is a one-way hash
 * of the slug. Favourites and history only store the id — with the reverse map living in
 * memory, every entry opened after an app restart failed with "slug not in cache".
 *
 * These pin the fallback that makes a restart survivable.
 */
class EmbedSlugResolutionTest {

    @Test fun `memory hit does not touch the database`() = runTest {
        var dbCalls = 0
        val slug = resolveSlug(
            vodId = 42L,
            fromMemory = { "in-memory" },
            fromDb = { dbCalls++; "from-db" },
            remember = { _, _ -> },
        )
        assertThat(slug).isEqualTo("in-memory")
        assertThat(dbCalls).isEqualTo(0)
    }

    /** The restart case: memory is empty, the row is still in Room. */
    @Test fun `memory miss falls back to the database`() = runTest {
        val slug = resolveSlug(
            vodId = 42L,
            fromMemory = { null },
            fromDb = { "fns-203" },
            remember = { _, _ -> },
        )
        assertThat(slug).isEqualTo("fns-203")
    }

    /** Re-reading Room on every episode of a binge would be wasteful. */
    @Test fun `a database hit is written back into memory`() = runTest {
        val remembered = mutableMapOf<Long, String>()
        resolveSlug(
            vodId = 42L,
            fromMemory = { null },
            fromDb = { "fns-203" },
            remember = { id, s -> remembered[id] = s },
        )
        assertThat(remembered).containsExactly(42L, "fns-203")
    }

    @Test fun `unknown id yields null rather than throwing`() = runTest {
        val slug = resolveSlug(42L, fromMemory = { null }, fromDb = { null }, remember = { _, _ -> })
        assertThat(slug).isNull()
    }

    /**
     * A failing lookup must degrade to the old behaviour (an actionable ScraperException
     * upstream), never crash the detail screen.
     */
    @Test fun `database errors degrade to null`() = runTest {
        val slug = resolveSlug(
            vodId = 42L,
            fromMemory = { null },
            fromDb = { error("Room unavailable") },
            remember = { _, _ -> },
        )
        assertThat(slug).isNull()
    }
}
