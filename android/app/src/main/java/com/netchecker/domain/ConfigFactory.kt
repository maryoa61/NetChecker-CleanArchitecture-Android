package com.netchecker.domain

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Clean Architecture Utility: ConfigFactory
 * Singleton utility responsible for generating ready-to-use proxy configurations (VLESS, Trojan, VMess WS)
 * based on discovered IP addresses and optional subscription routing endpoints.
 */
object ConfigFactory {

    /**
     * Generates VLESS, Trojan, and WS (VMess WS) configs for a list of discovered IPs.
     * Combines all generated configurations into a single flat list of ready-to-use standard links.
     */
    fun generateConfigsForIps(
        ips: List<String>,
        subscriptionUrl: String? = null
    ): List<String> {
        return ips.flatMap { ip ->
            generateConfigsForIp(ip, subscriptionUrl)
        }
    }

    /**
     * Generates a suite of standard proxy configs (VLESS WS, Trojan WS, VMess WS) for a given IP address.
     * Fallbacks to Port 443, SNI "cdn.cloudflare.net", and Path "/" if no subscription endpoint is supplied.
     */
    fun generateConfigsForIp(
        ip: String,
        subscriptionUrl: String? = null
    ): List<String> {
        var port = 443
        var sni = "cdn.cloudflare.net"
        var path = "/"

        if (!subscriptionUrl.isNullOrBlank()) {
            try {
                val parsedUrl = java.net.URL(subscriptionUrl)
                sni = parsedUrl.host
                port = if (parsedUrl.port != -1) parsedUrl.port else {
                    if (parsedUrl.protocol.equals("https", ignoreCase = true)) 443 else 80
                }
                val rawPath = parsedUrl.path
                path = if (rawPath.isNullOrEmpty()) "/" else rawPath
                if (parsedUrl.query != null) {
                    path += "?" + parsedUrl.query
                }
            } catch (e: Exception) {
                // If the input is not a full URL, fallback and treat as raw host/SNI, keeping default port and path
                sni = subscriptionUrl.trim()
            }
        }

        val vlessConfig = generateVless(ip, port, sni, path)
        val trojanConfig = generateTrojan(ip, port, sni, path)
        val wsConfig = generateVmessWs(ip, port, sni, path)

        return listOf(vlessConfig, trojanConfig, wsConfig)
    }

    /**
     * Generates VLESS configuration in URIs format compatible with V2Ray client structures.
     */
    private fun generateVless(
        ip: String,
        port: Int,
        sni: String,
        path: String
    ): String {
        val uuid = UUID.randomUUID().toString()
        val remarks = "VLESS-$ip-$port"
        val encodedRemarks = encodeRemarks(remarks)
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        
        return "vless://$uuid@$ip:$port?encryption=none&security=tls&sni=$sni&type=ws&host=$sni&path=$encodedPath#$encodedRemarks"
    }

    /**
     * Generates Trojan configuration in URIs format compatible with standard proxy clients.
     */
    private fun generateTrojan(
        ip: String,
        port: Int,
        sni: String,
        path: String
    ): String {
        val password = UUID.randomUUID().toString()
        val remarks = "Trojan-$ip-$port"
        val encodedRemarks = encodeRemarks(remarks)
        val encodedPath = URLEncoder.encode(path, "UTF-8")

        return "trojan://$password@$ip:$port?security=tls&sni=$sni&type=ws&host=$sni&path=$encodedPath#$encodedRemarks"
    }

    /**
     * Generates standard VMess over WebSocket configuration (WS config) formatted base64 JSON string.
     */
    private fun generateVmessWs(
        ip: String,
        port: Int,
        sni: String,
        path: String
    ): String {
        val remarks = "WS-$ip-$port"
        val uuid = UUID.randomUUID().toString()

        val json = JSONObject()
        json.put("v", "2")
        json.put("ps", remarks)
        json.put("add", ip)
        json.put("port", port)
        json.put("id", uuid)
        json.put("aid", "0")
        json.put("scy", "auto")
        json.put("net", "ws")
        json.put("type", "none")
        json.put("host", sni)
        json.put("path", path)
        json.put("tls", "tls")
        json.put("sni", sni)

        val b64 = Base64.encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return "vmess://$b64"
    }

    /**
     * Helper method to url-encode fragments for Remarks and parameters.
     */
    private fun encodeRemarks(remarks: String): String {
        return try {
            URLEncoder.encode(remarks, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            remarks
        }
    }
}
