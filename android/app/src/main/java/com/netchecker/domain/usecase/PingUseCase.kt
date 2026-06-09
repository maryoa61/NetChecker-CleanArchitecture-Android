package com.netchecker.domain.usecase

import com.netchecker.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clean Architecture Use Case: True-HTTP Ping.
 * Conducts raw HTTP Head pings through the custom OkHttp client instance.
 */
class PingUseCase {

    /**
     * Dispatches deep I/O execution to verify full HTTP communication roundtrip performance.
     * Useful for checking complete stack accessibility over port 80/443 protocols.
     * 
     * @param url Target endpoint (e.g., "https://www.cloudflare.com" or a CDN link).
     * @return Pair containing boolean success indicator and response speed in milliseconds.
     */
    suspend fun execute(url: String): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        HttpClient.pingHttp(url)
    }
}
