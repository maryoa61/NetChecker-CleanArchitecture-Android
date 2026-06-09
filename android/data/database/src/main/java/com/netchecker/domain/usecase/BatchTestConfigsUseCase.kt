package com.netchecker.domain.usecase

import com.netchecker.core.network.HttpClient
import com.netchecker.data.database.ProxyConfigDao
import com.netchecker.data.database.ProxyConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URI

class BatchTestConfigsUseCase(private val proxyConfigDao: ProxyConfigDao) {

    /**
     * Executes concurrent ping/reachtability evaluations on all provided proxy profiles.
     * Restricts background threads using Semaphore to prevent bandwidth exhaustion.
     * Streams total completion progress and latest evaluated entity.
     */
    fun execute(
        proxies: List<ProxyConfigEntity>,
        maxConcurrency: Int = 30,
        timeoutMs: Int = 1200
    ): Flow<Pair<Int, ProxyConfigEntity>> = channelFlow {
        val semaphore = Semaphore(maxConcurrency)
        val total = proxies.size
        var completed = 0

        withContext(Dispatchers.IO) {
            val jobs = proxies.map { proxy ->
                async {
                    semaphore.withPermit {
                        val hostAndPort = extractHostAndPort(proxy.rawConfig)
                        val start = System.currentTimeMillis()
                        val isWorking = try {
                            HttpClient.client.socketFactory.createSocket().use { socket ->
                                socket.connect(InetSocketAddress(hostAndPort.first, hostAndPort.second), timeoutMs)
                                true
                            }
                        } catch (e: Exception) {
                            false
                        }
                        val elapsed = if (isWorking) System.currentTimeMillis() - start else -1L

                        val evaluated = proxy.copy(
                            currentPing = elapsed,
                            isWorking = isWorking
                        )
                        proxyConfigDao.updateProxy(evaluated)
                        completed++
                        send(Pair(completed, evaluated))
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    /**
     * Helper to parse and extract target server IP/Host and Port from any protocol URL.
     */
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
                // Emulated b64 decoding for target discovery
                val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val json = org.json.JSONObject(String(decoded, java.nio.charset.StandardCharsets.UTF_8))
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
