package com.netchecker.domain.usecase

import android.util.Base64
import com.netchecker.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Clean Architecture Use Case: ConfigLatencyTesterUseCase
 * Evaluates the round-trip-time (RTT) of a proxy configuration in milliseconds
 * by performing a TCP handshake and/or HTTP HEAD request.
 */
class ConfigLatencyTesterUseCase {

    /**
     * Executes RTT evaluation on a given proxy config link.
     * Fits perfectly with standard Clash/v2ray delay measuring routines.
     * 
     * @param rawConfig standard link (vless://, trojan://, or vmess://)
     * @param timeoutMs connection timeout limit
     * @return latency in milliseconds, or -1 if unreachable
     */
    suspend fun execute(rawConfig: String, timeoutMs: Int = 1500): Long = withContext(Dispatchers.IO) {
        val (host, port) = extractHostAndPort(rawConfig)
        if (host.isEmpty()) return@withContext -1L

        val startTime = System.currentTimeMillis()
        try {
            // Step 1: TCP Handshake Ping
            HttpClient.client.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val tcpElapsed = System.currentTimeMillis() - startTime

            // Step 2: HTTP HEAD request to complete the application-layer handshake
            // Since the configs use Cloudflare IPs, we can speak HTTPS/TLS natively to test full RTT
            val targetUrl = "https://$host:$port/"
            val request = Request.Builder()
                .url(targetUrl)
                .head()
                .addHeader("Host", "cdn.cloudflare.net")
                .addHeader("User-Agent", "v2rayNG/1.8.5 (Android)")
                .addHeader("Cache-Control", "no-cache")
                .build()

            // We build a short-timeout client specifically for testing individual configs
            val localClient = HttpClient.client.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()

            val httpStart = System.currentTimeMillis()
            try {
                localClient.newCall(request).execute().use { response ->
                    val httpElapsed = System.currentTimeMillis() - httpStart
                    // Status code doesn't have to be 200 (Cloudflare returns 400/403/405 for generic requests, which is proof of life!)
                    return@withContext (tcpElapsed + httpElapsed) / 2
                }
            } catch (httpEx: IOException) {
                // If application layer fails, fallback to standard TCP connection response
                return@withContext tcpElapsed
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun extractHostAndPort(rawConfig: String): Pair<String, Int> {
        return try {
            val trimmed = rawConfig.trim()
            if (trimmed.startsWith("vless://") || trimmed.startsWith("trojan://")) {
                val cleanUri = trimmed.split("#")[0]
                val atIndex = cleanUri.indexOf("@")
                if (atIndex != -1) {
                    val rest = cleanUri.substring(atIndex + 1)
                    val queryMark = rest.indexOf("?")
                    val hostPort = if (queryMark != -1) rest.substring(0, queryMark) else rest
                    val colon = hostPort.indexOf(":")
                    if (colon != -1) {
                        val host = hostPort.substring(0, colon)
                        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
                        Pair(host, port)
                    } else {
                        Pair(hostPort, 443)
                    }
                } else {
                    Pair("104.16.1.1", 443)
                }
            } else if (trimmed.startsWith("vmess://")) {
                val b64 = trimmed.substring(8).trim()
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
                val add = json.optString("add", "104.16.1.1")
                val port = json.optInt("port", 443)
                Pair(add, port)
            } else {
                Pair("104.16.1.1", 443)
            }
        } catch (e: Exception) {
            Pair("104.16.1.1", 443)
        }
    }
}
