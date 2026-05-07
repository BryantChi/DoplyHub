package com.gimy.tv.data.update

/** Update-related options parsed from endpoints.json's `update` block. */
data class UpdateConfig(
    val minSupportedVersion: SemVer,
    val releaseRepo: String,
    val forceUpdateMessage: String,
) {
    companion object {
        val DEFAULT = UpdateConfig(
            minSupportedVersion = SemVer(1, 0, 0),
            releaseRepo = "BryantChi/DoplyHub",
            forceUpdateMessage = "您的版本過舊，已不再支援。請更新後繼續使用。",
        )
    }
}
