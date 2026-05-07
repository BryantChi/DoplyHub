package com.gimy.tv.data.update

import android.content.Context
import android.content.pm.PackageManager
import com.gimy.tv.data.endpoint.EndpointResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub Releases for newer APKs of this app.
 *
 * Flow:
 *   1. Ensure EndpointResolver has up-to-date UpdateConfig (calls refreshIfStale).
 *   2. Read current versionName from PackageManager.
 *   3. Fetch /repos/{owner}/{repo}/releases/latest from GitHub API.
 *   4. Compare tag → SemVer → UpdateInfo.
 *
 * Mandatory rule: if current version < minSupportedVersion (from endpoints.json),
 * the available update is marked mandatory regardless of whether the user dismissed it before.
 */
@Singleton
class AppUpdateService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) {
    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val FETCH_TIMEOUT_MS = 8000L
    }

    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        // Ensure update config is fresh (24h TTL inside resolver)
        endpointResolver.refreshIfStale()
        val updateConfig = endpointResolver.getUpdateConfig()

        val currentName = currentVersionName()
        val current = SemVer.parse(currentName)
            ?: return@withContext UpdateInfo.Error("無法解析目前版本號 ($currentName)")

        val release = try {
            fetchLatestRelease(updateConfig.releaseRepo)
        } catch (e: Exception) {
            return@withContext UpdateInfo.Error("無法連線到更新伺服器")
        }
        // null = success but no release published yet — treat as up-to-date
        if (release == null) return@withContext UpdateInfo.UpToDate

        val latest = SemVer.parse(release.tagName)
            ?: return@withContext UpdateInfo.Error("最新版本標籤格式錯誤: ${release.tagName}")

        val needsForce = current < updateConfig.minSupportedVersion

        when {
            // Newer release exists
            latest > current -> UpdateInfo.Available(
                currentVersion = current,
                latestVersion = latest,
                downloadUrl = release.apkUrl
                    ?: return@withContext UpdateInfo.Error("最新版本未提供 APK 下載連結"),
                sizeBytes = release.apkSize,
                changelog = release.body.ifBlank { "無更新說明" },
                isMandatory = needsForce,
                mandatoryMessage = updateConfig.forceUpdateMessage,
            )
            // No newer release but current is below minSupported — rare edge case where the
            // server raised the floor without a corresponding release. Still force update,
            // pointing at whatever latest is available.
            needsForce && release.apkUrl != null -> UpdateInfo.Available(
                currentVersion = current,
                latestVersion = latest,
                downloadUrl = release.apkUrl,
                sizeBytes = release.apkSize,
                changelog = release.body,
                isMandatory = true,
                mandatoryMessage = updateConfig.forceUpdateMessage,
            )
            else -> UpdateInfo.UpToDate
        }
    }

    private fun currentVersionName(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName
    }.getOrNull()

    private suspend fun fetchLatestRelease(repo: String): GitHubRelease? = withTimeout(FETCH_TIMEOUT_MS) {
        val url = "$GITHUB_API_BASE/repos/$repo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            // 404 = no releases published yet — treat as "no update available" rather than an error
            if (resp.code == 404) return@withTimeout null
            if (!resp.isSuccessful) error("GitHub API ${resp.code}")
            val body = resp.body?.string() ?: error("Empty response")
            parseRelease(body)
        }
    }

    private fun parseRelease(json: String): GitHubRelease {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        val body = root.optString("body", "").trim()
        val assets = root.optJSONArray("assets")
        var apkUrl: String? = null
        var apkSize = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        return GitHubRelease(tagName = tag, body = body, apkUrl = apkUrl, apkSize = apkSize)
    }

    private data class GitHubRelease(
        val tagName: String,
        val body: String,
        val apkUrl: String?,
        val apkSize: Long,
    )
}
