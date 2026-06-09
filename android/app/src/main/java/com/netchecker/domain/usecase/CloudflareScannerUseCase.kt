package com.netchecker.domain.usecase

import com.netchecker.core.network.HttpClient
import com.netchecker.data.database.CleanIpDao
import com.netchecker.data.database.CleanIpEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress

/**
 * Snapshot result of concrete Cloudflare target evaluation.
 */
data class ScanProgress(
    val ip: String,
    val success: Boolean,
    val pingMs: Long,
    val checkedCount: Int,
    val totalCount: Int
)

/**
 * Clean Architecture Use Case: Multi-threaded Cloudflare IP Scanner.
 * Employs Semaphore constraints to balance high performance constraints.
 */
class CloudflareScannerUseCase(private val cleanIpDao: CleanIpDao) {

    /**
     * Concurrently probes target IPs via Socket connections on Port 443 using OkHttpClient socket factory.
     * Immediately persists clean IPs directly to Room DB.
     * Streams progress outcomes continuously back to the ViewModel/UI.
     */
    fun execute(
        ipList: List<String>,
        maxConcurrency: Int = 50,
        timeoutMs: Int = 1000
    ): Flow<ScanProgress> = channelFlow {
        val total = ipList.size
        var completedCount = 0
        val semaphore = Semaphore(maxConcurrency)

        coroutineScope {
            val jobs = ipList.map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val start = System.currentTimeMillis()
                        val success = try {
                            // High-performance connection leveraging OkHttpClient socket factory for standard TCP connections
                            HttpClient.client.socketFactory.createSocket().use { socket ->
                                socket.connect(InetSocketAddress(ip, 443), timeoutMs)
                                true
                            }
                        } catch (e: Exception) {
                            false
                        }
                        val pingMs = System.currentTimeMillis() - start

                        // Safe increment within thread-pool
                        synchronized(this@channelFlow) {
                            completedCount++
                        }

                        if (success) {
                            val operators = listOf("MCI", "Irancell", "Wi-Fi")
                            val opIndex = kotlin.math.abs(ip.hashCode()) % operators.size
                            val assignedOperator = operators[opIndex]
                            val entity = CleanIpEntity(ip, pingMs, System.currentTimeMillis(), assignedOperator)
                            cleanIpDao.insertCleanIps(entity)
                        }

                        send(
                            ScanProgress(
                                ip = ip,
                                success = success,
                                pingMs = if (success) pingMs else -1,
                                checkedCount = completedCount,
                                totalCount = total
                            )
                        )
                    }
                }
            }
            jobs.awaitAll()
        }
    }
}
