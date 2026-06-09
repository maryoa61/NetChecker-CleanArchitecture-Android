package com.netchecker.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Singleton-based custom HTTP client provider using OkHttp.
 * Configured specifically with tight connection timeouts suited for rapid network scanning.
 */
object HttpClient {
    
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .writeTimeout(1000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Executes an HTTP GET / HEAD ping to check if the target endpoint is reachable.
     * Computes the exact millisecond duration, mimicking a standard network latency command.
     * Incorporates cache-busting headers for measurement accuracy (bypassing proxies and CDNs).
     * Falls back dynamically from a fast HEAD request to a standard GET instruction if required.
     * 
     * @param url The full HTTP/HTTPS url to query.
     * @return Pair of Boolean (isSuccessful) and Long (responseTimeMs).
     */
    fun pingHttp(url: String): Pair<Boolean, Long> {
        val headRequest = Request.Builder()
            .url(url)
            .head() // Try fast HEAD method first to minimize payload
            .addHeader("User-Agent", "NetChecker/1.0.0 (Android; Cloudflare Scanner)")
            .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            .addHeader("Pragma", "no-cache")
            .build()

        val startTime = System.currentTimeMillis()
        try {
            client.newCall(headRequest).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                if (response.isSuccessful || response.code == 403 || response.code == 405) {
                    return Pair(true, elapsed)
                }
            }
        } catch (e: IOException) {
            // Roll back to full GET request if HEAD is rejected or not supported by intermediate routers
        }

        // Full GET fallback for high-fidelity HTTP handshake latency calculation
        val getRequest = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "NetChecker/1.0.0 (Android; Cloudflare Scanner)")
            .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            .addHeader("Pragma", "no-cache")
            .build()

        val getStartTime = System.currentTimeMillis()
        return try {
            client.newCall(getRequest).execute().use { response ->
                val elapsed = System.currentTimeMillis() - getStartTime
                Pair(response.isSuccessful || response.code == 403, elapsed)
            }
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - getStartTime
            Pair(false, elapsed)
        }
    }
}
