package com.miku.ray.handler

import com.miku.ray.AppConfig
import com.miku.ray.dto.IPAPIInfo
import com.miku.ray.dto.UrlContentRequest
import com.miku.ray.util.HttpUtil
import com.miku.ray.util.JsonUtil
import com.miku.ray.util.LogUtil
import com.miku.ray.util.Utils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object SpeedtestManager {

    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getCountryCodeThroughProxy(httpPort: Int, timeoutMs: Int = 3500): String? {
        if (httpPort <= 0) return null

        val configuredUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
        .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL
        val url = configuredUrl.replace("{ip}", "", ignoreCase = true)
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = timeoutMs,
                httpPort = httpPort
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        return countryCode(ipInfo)
    }

    fun countryCode(info: IPAPIInfo): String? = listOf(
        info.country_code, info.countryCode, info.location?.country_code, info.country,
    ).asSequence().filterNotNull().map { it.trim().uppercase(java.util.Locale.ROOT) }
        .firstOrNull { it.matches(Regex("[A-Z]{2}")) }

    suspend fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
        .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        // A temporary API outage must not erase a valid IP. Try independent
        // endpoints through the same mixed inbound, never directly from the app.
        // They race in parallel so one slow endpoint cannot hold back the answer.
        val urls = listOf(url.replace("{ip}", "", ignoreCase = true),
            "https://api.ipify.org?format=json", "https://api.ip.sb/geoip").distinct()

        val winner = CompletableDeferred<Pair<IPAPIInfo, String>>()
        val found = coroutineScope {
            val probes = urls.map { endpoint ->
                async(Dispatchers.IO) {
                    val candidate = runCatching {
                        val content = HttpUtil.getUrlContent(UrlContentRequest(
                            url = endpoint, timeout = 3000, httpPort = httpPort,
                            proxyUsername = proxyUsername, proxyPassword = proxyPassword,
                        )) ?: return@runCatching null
                        JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java)
                    }.getOrNull() ?: return@async
                    val candidateIp = listOf(candidate.ip, candidate.clientIp, candidate.ip_addr, candidate.query)
                        .firstOrNull { !it.isNullOrBlank() && Utils.isPureIpAddress(it) }
                        ?: return@async
                    winner.complete(candidate to candidateIp)
                }
            }
            // Once any endpoint answers (or none does within the window), the
            // remaining probes are cancelled and their sockets abandoned.
            val result = withTimeoutOrNull(3500L) { winner.await() }
            probes.forEach { it.cancel() }
            result
        } ?: return null
        val (ipInfo, ip) = found

        val country = countryCode(ipInfo)

        val showIsp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_ISP_INFO, true)
        val isp = if (showIsp) {
            listOf(
                ipInfo.isp,
                ipInfo.organization,
                ipInfo.org,
                ipInfo.asn_organization,
                ipInfo.asOrg,
                ipInfo.asname
            ).firstOrNull { !it.isNullOrBlank() }
        } else {
            null
        }

        val flag = Utils.countryCodeToFlag(country)
        val flagPrefix = if (flag.isNotEmpty()) "$flag " else ""
        val ispSuffix = if (!isp.isNullOrBlank()) " · $isp" else ""
        return if (country.isNullOrBlank()) "$ip$ispSuffix" else "${flagPrefix}($country) $ip$ispSuffix"
    }
}
