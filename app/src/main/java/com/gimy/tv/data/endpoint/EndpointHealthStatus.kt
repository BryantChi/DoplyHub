package com.gimy.tv.data.endpoint

import com.gimy.tv.domain.model.SourceType

/**
 * How a source's currently selected endpoint is doing.
 *
 * The 2026-09 outage was invisible because a broken endpoint still answered HTTP 200: the
 * parser matched nothing, the HEAD fallback succeeded, and the app kept serving it. These
 * states exist so that condition surfaces instead of looking like an empty catalogue.
 */
enum class EndpointHealthStatus(val label: String) {
    HEALTHY("正常"),
    /** Parsed something, but far short of a page — usually a partial template change. */
    DEGRADED("解析異常"),
    /** HTTP may be fine, yet nothing parsed. This is the silent-failure signature. */
    BROKEN("失效"),
    /** Source has no parser-based probe; absence of evidence, not a fault. */
    UNKNOWN("未探測");

    val needsAttention: Boolean get() = this == DEGRADED || this == BROKEN
}

/** Classifies a probe result; [itemCount] of -1 means the source cannot be probed. */
internal fun healthStatusOf(itemCount: Int, minItems: Int): EndpointHealthStatus = when {
    itemCount < 0 -> EndpointHealthStatus.UNKNOWN
    itemCount == 0 -> EndpointHealthStatus.BROKEN
    itemCount < minItems -> EndpointHealthStatus.DEGRADED
    else -> EndpointHealthStatus.HEALTHY
}

/** A single source's last probe result, surfaced in Settings. */
data class EndpointHealth(
    val sourceType: SourceType,
    val url: String,
    val profile: String?,
    val itemCount: Int,
    val status: EndpointHealthStatus,
    val checkedAt: Long,
)
