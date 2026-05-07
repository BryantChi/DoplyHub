package com.gimy.tv.data.update

sealed class UpdateInfo {
    data object UpToDate : UpdateInfo()

    data class Available(
        val currentVersion: SemVer,
        val latestVersion: SemVer,
        val downloadUrl: String,
        val sizeBytes: Long,
        val changelog: String,
        val isMandatory: Boolean,
        val mandatoryMessage: String,
    ) : UpdateInfo()

    data class Error(val message: String) : UpdateInfo()
}
