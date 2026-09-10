package com.gimy.tv.data.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The 2026-09 outage was silent: the resolver correctly judged both Gimy mirrors unhealthy,
 * fell back to a HEAD probe that returned 200, and kept serving a broken endpoint with no
 * signal anywhere. Classifying the probe result is what makes that visible.
 */
class EndpointHealthStatusTest {

    private val min = 5

    @Test fun `a full list is healthy`() {
        assertThat(healthStatusOf(itemCount = 42, minItems = min)).isEqualTo(EndpointHealthStatus.HEALTHY)
    }

    @Test fun `exactly the threshold is healthy`() {
        assertThat(healthStatusOf(itemCount = 5, minItems = min)).isEqualTo(EndpointHealthStatus.HEALTHY)
    }

    /** Parsing something but well short of a page usually means the template shifted. */
    @Test fun `a short list is degraded`() {
        assertThat(healthStatusOf(itemCount = 2, minItems = min)).isEqualTo(EndpointHealthStatus.DEGRADED)
    }

    /** Zero is the signature of the silent failure: HTTP fine, parser matched nothing. */
    @Test fun `zero items is broken`() {
        assertThat(healthStatusOf(itemCount = 0, minItems = min)).isEqualTo(EndpointHealthStatus.BROKEN)
    }

    /** -1 means the source has no parser-based probe at all — not a fault. */
    @Test fun `negative means the source cannot be probed`() {
        assertThat(healthStatusOf(itemCount = -1, minItems = min)).isEqualTo(EndpointHealthStatus.UNKNOWN)
    }

    @Test fun `broken and degraded both count as needing attention`() {
        assertThat(EndpointHealthStatus.BROKEN.needsAttention).isTrue()
        assertThat(EndpointHealthStatus.DEGRADED.needsAttention).isTrue()
        assertThat(EndpointHealthStatus.HEALTHY.needsAttention).isFalse()
        assertThat(EndpointHealthStatus.UNKNOWN.needsAttention).isFalse()
    }
}
