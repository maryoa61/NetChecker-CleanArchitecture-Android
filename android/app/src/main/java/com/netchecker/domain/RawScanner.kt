package com.netchecker.domain

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Result data class for RawScanner representing the outcome of scanning a single IP.
 */
data class ScanResult(
    val ip: String,
    val ping: Long,
    val port: Int
)

/**
 * RawScanner - Optimized class for scanning a single raw IP address with Port Hopping,
 * TLS handshake verification, and warm-up checks.
 */
class RawScanner {

    // Define standard alternative ports for proxy / CDN handshake checks
    private val alternativePorts = listOf(443, 2053, 2083, 2087, 8443)

    /**
     * Scans an IP across multiple alternative ports using a real SSL/TLS handshake and a warm-up packet.
     * Guarantees accurate latency measurement without negative values.
     *
     * @param ip The target IP address to verify and ping.
     * @return ScanResult containing IP, successful ping latency in ms, and the working port (or ping = -1 and port = -1 on failure).
     */
    suspend fun checkIp(ip: String): ScanResult {
        return withContext(Dispatchers.IO) {
            for (port in alternativePorts) {
                val startTime = SystemClock.elapsedRealtime()
                var socket: Socket? = null
                var sslSocket: SSLSocket? = null
                try {
                    // Step 1: Create a raw TCP socket and connect to the port
                    socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), 1500)
                    
                    // Step 2: Layer SSL/TLS on top of the established TCP connection
                    val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    sslSocket = sslSocketFactory.createSocket(socket, ip, port, true) as SSLSocket
                    
                    // Configure timeouts for the TLS socket
                    sslSocket.soTimeout = 1500
                    
                    // Step 3: Perform the actual TLS Handshake
                    sslSocket.startHandshake()

                    // Step 4: Measure accurate elapsed time up to successful TLS Handshake completion
                    // This excludes the warm-up response delay from the final ping latency to ensure accuracy.
                    val handshakedTime = SystemClock.elapsedRealtime()
                    val latency = handshakedTime - startTime

                    // Step 5: Warm-up phase (Quality Validation)
                    // Set short timeout (1000ms) specifically for reading warm-up response
                    sslSocket.soTimeout = 1000

                    val warmUpPayload = "GET / HTTP/1.1\r\nHost: cloudflare.com\r\nConnection: close\r\n\r\n"
                    val os = sslSocket.outputStream
                    os.write(warmUpPayload.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    os.flush()

                    val ins = sslSocket.inputStream
                    val bytesRead = ins.read() // Read the first byte of response to confirm incoming data flow
                    if (bytesRead == -1) {
                        throw java.io.IOException("Unreliable connection: Server returned EOF during warm-up")
                    }
                    
                    // Verify latency is valid and return successful result
                    if (latency >= 0) {
                        return@withContext ScanResult(
                            ip = ip,
                            ping = latency,
                            port = port
                        )
                    }
                } catch (e: Exception) {
                    // Failures fall through to try next port in the list
                } finally {
                    // Close sockets cleanly 
                    try {
                        sslSocket?.close()
                    } catch (ignored: Exception) {}
                    try {
                        socket?.close()
                    } catch (ignored: Exception) {}
                }
            }
            
            // If all ports fail/timeout, return a failed scan result
            ScanResult(ip = ip, ping = -1L, port = -1)
        }
    }
}
