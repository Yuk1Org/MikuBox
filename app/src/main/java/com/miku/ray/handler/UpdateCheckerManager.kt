package com.miku.ray.handler

import android.os.Build
import com.miku.ray.AppConfig
import com.miku.ray.BuildConfig
import com.miku.ray.dto.CheckUpdateResult
import com.miku.ray.dto.GitHubRelease
import com.miku.ray.dto.UrlContentRequest
import com.miku.ray.extension.concatUrl
import com.miku.ray.util.HttpUtil
import com.miku.ray.util.JsonUtil
import com.miku.ray.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
            ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
            ?.firstOrNull()
            ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                releaseUrl = latestRelease.htmlUrl.ifBlank {
                    AppConfig.APP_API_URL.concatUrl("latest")
                },
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    // Version tags follow vX.XX.X-NAME-PATCH — the leading triple is the
    // major version, NAME labels the release line, and the trailing number
    // is the small patch version. The triple decides first; a different
    // name on an equal triple is a newer line by definition (GitHub only
    // reports its latest release here); the patch orders within one name.
    // Anything malformed falls back to comparing the digit runs in order,
    // which also handles this app's older "UwU-1.0.0" style.
    private val versionPattern = Regex("v?([0-9]+(?:\\.[0-9]+)*)-([A-Za-z]+)-([0-9]+)")
    private val digitRuns = Regex("\\d+")

    private fun compareVersions(version1: String, version2: String): Int {
        val match1 = versionPattern.matchEntire(version1.trim())
        val match2 = versionPattern.matchEntire(version2.trim())
        if (match1 == null || match2 == null) {
            val runs1 = digitRuns.findAll(version1).map { it.value.toLong() }.toList()
            val runs2 = digitRuns.findAll(version2).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(runs1.size, runs2.size)) {
                val a = runs1.getOrElse(i) { 0L }
                val b = runs2.getOrElse(i) { 0L }
                if (a != b) return if (a < b) -1 else 1
            }
            return 0
        }

        fun triple(version: String) = version.split(".").map { it.toLong() }
        val triple1 = triple(match1.groupValues[1])
        val triple2 = triple(match2.groupValues[1])
        for (i in 0 until maxOf(triple1.size, triple2.size)) {
            val a = triple1.getOrElse(i) { 0L }
            val b = triple2.getOrElse(i) { 0L }
            if (a != b) return if (a < b) -1 else 1
        }

        val name1 = match1.groupValues[2]
        val name2 = match2.groupValues[2]
        if (!name1.equals(name2, ignoreCase = true)) return 1

        val patch1 = match1.groupValues[3].toLong()
        val patch2 = match2.groupValues[3].toLong()
        return patch1.compareTo(patch2)
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val assetsByAbi = release.assets.filter {
            (it.name.contains(abi, true))
        }

        val asset = if (BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)) {
            assetsByAbi.firstOrNull { it.name.contains(fDroid) }
        } else {
            assetsByAbi.firstOrNull { !it.name.contains(fDroid) }
        }

        return asset?.browserDownloadUrl
        ?: throw IllegalStateException("No compatible APK found")
    }
}
