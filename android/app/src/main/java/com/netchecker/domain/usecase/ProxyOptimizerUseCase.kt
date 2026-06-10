package com.netchecker.domain.usecase

import android.util.Base64
import com.netchecker.data.database.CleanIpDao
import com.netchecker.data.database.ProxyConfigDao
import com.netchecker.data.database.ProxyConfigEntity
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Clean Architecture Use Case: Proxy Link Optimizer.
 * Extracts raw targets from paste inputs, replaces endpoint parameters with 
 * discovered Clean IPs, retains origin properties as host routing, and saves to Room.
 */
class ProxyOptimizerUseCase(
    private val cleanIpDao: CleanIpDao,
    private val proxyConfigDao: ProxyConfigDao
) {

    /**
     * Parses custom multi-line or single paste strings of proxy gate protocols,
     * updates targets to the fastest evaluated socket, and inserts into Room DB.
     */
    suspend fun optimizeAndSave(rawInput: String): List<ProxyConfigEntity> {
        val lines = rawInput.split(Regex("[\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val optimizedList = mutableListOf<ProxyConfigEntity>()

        // Find fastest clean IP, fall back to default CF space if scanning has not finished
        val cleanIps = cleanIpDao.getAllCleanIps()
        val fastestIp = cleanIps.minByOrNull { it.pingMs }?.ipAddress ?: "104.16.1.1"

        for (line in lines) {
            try {
                val optimizedConfig = when {
                    line.startsWith("vless://") -> optimizeVlessOrTrojan(line, "vless", fastestIp)
                    line.startsWith("trojan://") -> optimizeVlessOrTrojan(line, "trojan", fastestIp)
                    line.startsWith("vmess://") -> optimizeVmess(line, fastestIp)
                    else -> null
                }
                if (optimizedConfig != null) {
                    val entity = ProxyConfigEntity(
                        remarks = optimizedConfig.first,
                        rawConfig = optimizedConfig.second,
                        currentPing = -1,
                        isWorking = false
                    )
                    val insertedId = proxyConfigDao.insertProxy(entity)
                    optimizedList.add(entity.copy(id = insertedId.toInt()))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return optimizedList
    }

    private fun optimizeVlessOrTrojan(uriStr: String, protocol: String, cleanIp: String): Pair<String, String>? {
        val schemePrefix = "$protocol://"
        if (!uriStr.startsWith(schemePrefix)) return null

        val fragmentIndex = uriStr.indexOf('#')
        val queryAndFragment = if (fragmentIndex != -1) uriStr.substring(schemePrefix.length, fragmentIndex) else uriStr.substring(schemePrefix.length)
        val originalRemarks = if (fragmentIndex != -1) URLDecoder.decode(uriStr.substring(fragmentIndex + 1), "UTF-8") else "Optimized-Gate"
        val newRemarks = if (originalRemarks.endsWith("-Optimized")) originalRemarks else "$originalRemarks-Optimized"

        val atIndex = queryAndFragment.indexOf('@')
        if (atIndex == -1) return null
        val auth = queryAndFragment.substring(0, atIndex)
        val rest = queryAndFragment.substring(atIndex + 1)

        val queryMarkIndex = rest.indexOf('?')
        val hostPortStr = if (queryMarkIndex != -1) rest.substring(0, queryMarkIndex) else rest
        val queryStr = if (queryMarkIndex != -1) rest.substring(queryMarkIndex + 1) else ""

        val portColonIndex = hostPortStr.indexOf(':')
        val originalHost = if (portColonIndex != -1) hostPortStr.substring(0, portColonIndex) else hostPortStr
        val port = if (portColonIndex != -1) hostPortStr.substring(portColonIndex + 1) else "443"

        // Ensure original domain is mapped to sni/host parameter for TLS handshake to be accurate
        val params = queryStr.split("&").filter { it.isNotEmpty() }.map {
            val parts = it.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) parts[1] else ""
            key to value
        }.toMap().toMutableMap()

        if (!params.containsKey("sni") || params["sni"].isNullOrBlank()) {
            params["sni"] = originalHost
        }
        if (!params.containsKey("host") || params["host"].isNullOrBlank()) {
            params["host"] = originalHost
        }

        val newQueryStr = params.map { "${it.key}=${it.value}" }.joinToString("&")
        val encodedRemarks = URLEncoder.encode(newRemarks, "UTF-8").replace("+", "%20")
        val optimizedUri = "$protocol://$auth@$cleanIp:$port?$newQueryStr#$encodedRemarks"

        return Pair(newRemarks, optimizedUri)
    }

    private fun optimizeVmess(uriStr: String, cleanIp: String): Pair<String, String>? {
        val schemePrefix = "vmess://"
        if (!uriStr.startsWith(schemePrefix)) return null
        val base64Part = uriStr.substring(schemePrefix.length).trim()
        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
        val jsonStr = String(decodedBytes, StandardCharsets.UTF_8)
        val json = JSONObject(jsonStr)

        val originalHost = json.optString("add", "1.1.1.1")
        val originalRemarks = json.optString("ps", "Gate")
        
        json.put("add", cleanIp)
        if (!json.has("host") || json.optString("host").isNullOrBlank()) {
            json.put("host", originalHost)
        }
        if (!json.has("sni") || json.optString("sni").isNullOrBlank()) {
            json.put("sni", originalHost)
        }
        
        val newRemarks = if (originalRemarks.endsWith("-Optimized")) originalRemarks else "$originalRemarks-Optimized"
        json.put("ps", newRemarks)

        val newJsonStr = json.toString()
        val newBase64 = Base64.encodeToString(newJsonStr.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        
        return Pair(newRemarks, "vmess://$newBase64")
    }
}
