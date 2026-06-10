package com.netchecker.domain

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RawScanner - A simple class for scanning a single raw IP address.
 */
class RawScanner {
    // یک کلاس ساده و خام برای تستِ فقط یک آیپی
    suspend fun checkIp(ip: String): Long {
        return withContext(Dispatchers.IO) {
            val startTime = SystemClock.elapsedRealtime()
            try {
                val socket = Socket()
                // پورت ۴۴۳ برای کلودفلر
                socket.connect(InetSocketAddress(ip, 443), 1500) 
                val endTime = SystemClock.elapsedRealtime()
                socket.close()
                return@withContext (endTime - startTime)
            } catch (e: Exception) {
                return@withContext -1L // اگر وصل نشد، کلاً برگرده منفی
            }
        }
    }
}
