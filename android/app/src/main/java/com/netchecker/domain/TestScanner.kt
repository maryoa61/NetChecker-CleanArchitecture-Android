package com.netchecker.domain

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TestScanner - A diagnostic utility object to prove basic network connectivity
 * and pinpoint handshake/SSL configuration errors.
 */
object TestScanner {
    private const val TAG = "TestScanner"
    private const val TARGET_IP = "104.16.0.10"
    private const val TARGET_PORT = 443
    private const val TIMEOUT_MS = 3000

    /**
     * Attempts to connect and run a full TLS handshake with a known-good IP (104.16.0.10).
     *
     * @return The handshake latency in milliseconds, or -1L if it fails.
     */
    suspend fun testConnectivity(): Long {
        return withContext(Dispatchers.IO) {
            val startTime = SystemClock.elapsedRealtime()
            var socket: Socket? = null
            var sslSocket: SSLSocket? = null
            
            Log.d(TAG, "[$TARGET_IP] Diagnostic test initiated.")
            try {
                // 1. Establish raw TCP Socket connection
                socket = Socket()
                Log.d(TAG, "[$TARGET_IP] Connecting raw TCP socket to port $TARGET_PORT...")
                socket.connect(InetSocketAddress(TARGET_IP, TARGET_PORT), TIMEOUT_MS)
                Log.d(TAG, "[$TARGET_IP] TCP socket connected successfully.")

                // 2. Upgrade raw socket to SSL Socket
                val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val upgraded = sslSocketFactory.createSocket(socket, TARGET_IP, TARGET_PORT, true) as SSLSocket
                sslSocket = upgraded

                // 3. Configure Server Name Indication (SNI) parameters
                val parameters = upgraded.sslParameters
                parameters.serverNames = listOf(SNIHostName("cloudflare.com"))
                upgraded.sslParameters = parameters
                upgraded.soTimeout = TIMEOUT_MS

                // 4. Perform TLS Handshake
                Log.d(TAG, "[$TARGET_IP] Starting TLS Handshake (SNI: cloudflare.com)...")
                upgraded.startHandshake()
                Log.d(TAG, "[$TARGET_IP] TLS Handshake completed successfully.")

                // 5. Calculate validation latency
                val endTime = SystemClock.elapsedRealtime()
                val latency = endTime - startTime
                Log.i(TAG, "[$TARGET_IP] Connectivity check succeeded! Latency: $latency ms")
                
                if (latency >= 0) latency else 0L
            } catch (authEx: javax.net.ssl.SSLHandshakeException) {
                Log.e(TAG, "[$TARGET_IP] Handshake Failed: ${authEx.message}", authEx)
                -1L
            } catch (peerUnreached: java.net.ConnectException) {
                Log.e(TAG, "[$TARGET_IP] Connection Refused: ${peerUnreached.message}", peerUnreached)
                -1L
            } catch (timeoutEx: java.io.InterruptedIOException) {
                Log.e(TAG, "[$TARGET_IP] Connection/Socket Timeout: ${timeoutEx.message}", timeoutEx)
                -1L
            } catch (e: Exception) {
                Log.e(TAG, "[$TARGET_IP] Diagnostic Check Failed with unexpected error: ${e.message}", e)
                -1L
            } finally {
                // Safely close connection streams
                try {
                    sslSocket?.close()
                } catch (ignored: Exception) {}
                try {
                    socket?.close()
                } catch (ignored: Exception) {}
            }
        }
    }
}
