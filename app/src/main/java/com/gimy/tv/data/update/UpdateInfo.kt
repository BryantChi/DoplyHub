package com.gimy.tv.data.update

sealed class UpdateInfo {
    data object UpToDate : UpdateInfo()

    data class Available(
        val currentVersion: SemVer,
        val latestVersion: SemVer,
        val downloadUrl: String,
        /** 主要來源連不上時改用這個。CDN 慢或沒同步到時，GitHub 的原始網址還在。 */
        val fallbackDownloadUrl: String? = null,
        val sizeBytes: Long,
        val changelog: String,
        val isMandatory: Boolean,
        val mandatoryMessage: String,
    ) : UpdateInfo()

    data class Error(val message: String) : UpdateInfo()
}
