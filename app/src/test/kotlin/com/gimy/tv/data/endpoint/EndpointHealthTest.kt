package com.gimy.tv.data.endpoint

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EndpointHealthTest {
    @Test fun `picks first candidate with count above threshold by priority`() = runTest {
        val counts = mapOf("a" to 0, "b" to 12, "c" to 20)
        val pick = pickHealthiest(listOf("a", "b", "c"), minItems = 5) { counts.getValue(it) }
        assertThat(pick).isEqualTo("b")
    }

    @Test fun `returns null when all candidates unhealthy`() = runTest {
        val pick = pickHealthiest(listOf("a", "b"), minItems = 5) { 0 }
        assertThat(pick).isNull()
    }

    @Test fun `probe returning -1 means unsupported and is skipped not counted healthy`() = runTest {
        val pick = pickHealthiest(listOf("a"), minItems = 5) { -1 }
        assertThat(pick).isNull()
    }

    @Test fun `probe throwing is treated as unhealthy`() = runTest {
        val pick = pickHealthiest(listOf("a"), minItems = 5) { error("network timeout") }
        assertThat(pick).isNull()
    }
}
