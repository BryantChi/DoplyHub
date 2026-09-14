package com.gimy.tv.data.endpoint

import com.gimy.tv.domain.model.EndpointHealthStatus

/** Classifies a probe result; [itemCount] of -1 means the source cannot be probed. */
internal fun healthStatusOf(itemCount: Int, minItems: Int): EndpointHealthStatus = when {
    itemCount < 0 -> EndpointHealthStatus.UNKNOWN
    itemCount == 0 -> EndpointHealthStatus.BROKEN
    itemCount < minItems -> EndpointHealthStatus.DEGRADED
    else -> EndpointHealthStatus.HEALTHY
}

/** A single source's last probe result, surfaced in Settings. */
