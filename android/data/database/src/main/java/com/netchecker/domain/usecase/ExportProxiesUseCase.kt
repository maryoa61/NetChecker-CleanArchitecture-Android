package com.netchecker.domain.usecase

import android.util.Base64
import com.netchecker.data.database.ProxyConfigEntity
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class ExportProxiesUseCase {

    enum class ExportFormat {
        V2RAY_OUTBOUNDS,
        CLASH_PROXIES,
        BASE64_SUBSCRIPTION
    }

    /**
     * Formats and exports 'Working' proxy configurations into standard formats.
     */
    fun execute(proxies: List<ProxyConfigEntity>, format: ExportFormat): String {
        val workingProxies = proxies.filter { it.isWorking }
        if (workingProxies.isEmpty()) {
            return when (format) {
                ExportFormat.V2RAY_OUTBOUNDS -> "{\"outbounds\":[]}"
                ExportFormat.CLASH_PROXIES -> "{\"proxies\":[]}"
                ExportFormat.BASE64_SUBSCRIPTION -> ""
            }
        }

        return when (format) {
            ExportFormat.V2RAY_OUTBOUNDS -> generateV2RayOutbounds(workingProxies)
            ExportFormat.CLASH_PROXIES -> generateClashProxies(workingProxies)
            ExportFormat.BASE64_SUBSCRIPTION -> generateBase64Subscription(workingProxies)
        }
    }

    private fun generateV2RayOutbounds(proxies: List<ProxyConfigEntity>): String {
        try {
            val root = JSONObject()
            val outbounds = JSONArray()

            for (proxy in proxies) {
                val parsed = parseProxyConfig(proxy)
                val outbound = JSONObject()
                outbound.put("tag", proxy.remarks)
                outbound.put("protocol", parsed.protocol)

                val settings = JSONObject()
                val vnext = JSONArray()
                val serverNode = JSONObject()
                serverNode.put("address", parsed.address)
                serverNode.put("port", parsed.port)

                val users = JSONArray()
                val user = JSONObject()
                user.put("id", parsed.uuid)
                user.put("encryption", parsed.encryption ?: "none")
                users.put(user)

                serverNode.put("users", users)
                vnext.put(serverNode)
                settings.put("vnext", vnext)
                outbound.put("settings", settings)

                // streamSettings
                val streamSettings = JSONObject()
                streamSettings.put("network", "tcp")
                if (parsed.tls == "tls") {
                    streamSettings.put("security", "tls")
                    val tlsSettings = JSONObject()
                    tlsSettings.put("serverName", parsed.sni ?: parsed.address)
                    streamSettings.put("tlsSettings", tlsSettings)
                }
                outbound.put("streamSettings", streamSettings)

                outbounds.put(outbound)
            }

            root.put("outbounds", outbounds)
            return root.toString(2)
        } catch (e: Exception) {
            return "{\"error\":\"Failed to generate V2Ray Outbounds: ${e.message}\"}"
        }
    }

    private fun generateClashProxies(proxies: List<ProxyConfigEntity>): String {
        try {
            val root = JSONObject()
            val proxiesArray = JSONArray()

            for (proxy in proxies) {
                val parsed = parseProxyConfig(proxy)
                val p = JSONObject()
                p.put("name", proxy.remarks)
                p.put("type", parsed.protocol)
                p.put("server", parsed.address)
                p.put("port", parsed.port)
                p.put("uuid", parsed.uuid)
                p.put("udp", true)
                p.put("tls", parsed.tls == "tls")
                if (parsed.sni != null) {
                    p.put("sni", parsed.sni)
                }
                if (parsed.host != null) {
                    p.put("host", parsed.host)
                }
                proxiesArray.put(p)
            }

            root.put("proxies", proxiesArray)
            return root.toString(2)
        } catch (e: Exception) {
            return "{\"error\":\"Failed to generate Clash proxies: ${e.message}\"}"
        }
    }

    private fun generateBase64Subscription(proxies: List<ProxyConfigEntity>): String {
        return try {
            val builder = StringBuilder()
            for (proxy in proxies) {
                builder.append(proxy.rawConfig.trim()).append("\n")
            }
            val bytes = builder.toString().toByteArray(StandardCharsets.UTF_8)
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    private data class ParsedProxy(
        val protocol: String,
        val uuid: String,
        val address: String,
        val port: Int,
        val tls: String?,
        val sni: String?,
        val host: String?,
        val encryption: String?
    )

    private fun parseProxyConfig(proxy: ProxyConfigEntity): ParsedProxy {
        val trimmed = proxy.rawConfig.trim()
        try {
            if (trimmed.startsWith("vless://") || trimmed.startsWith("trojan://")) {
                val protocol = if (trimmed.startsWith("vless://")) "vless" else "trojan"
                val cleanUri = trimmed.split("#")[0]
                val atIndex = cleanUri.indexOf("@")
                if (atIndex != -1) {
                    val uuid = cleanUri.substring(protocol.length + 3, atIndex)
                    val rest = cleanUri.substring(atIndex + 1)
                    val queryMark = rest.indexOf("?")
                    val hostPort = if (queryMark != -1) rest.substring(0, queryMark) else rest
                    val queryString = if (queryMark != -1) rest.substring(queryMark + 1) else ""

                    val colon = hostPort.indexOf(":")
                    val hostAddress = if (colon != -1) hostPort.substring(0, colon) else hostPort
                    val portVal = if (colon != -1) hostPort.substring(colon + 1).toIntOrNull() ?: 443 else 443

                    var sni: String? = null
                    var host: String? = null
                    var encryption: String? = null
                    var tls: String? = null

                    if (queryString.isNotEmpty()) {
                        val params = queryString.split("&")
                        for (param in params) {
                            val kv = param.split("=")
                            if (kv.size == 2) {
                                when (kv[0].lowercase()) {
                                    "sni" -> sni = kv[1]
                                    "host" -> host = kv[1]
                                    "encryption" -> encryption = kv[1]
                                    "security" -> if (kv[1].lowercase() == "tls") tls = "tls"
                                }
                            }
                        }
                    }

                    return ParsedProxy(protocol, uuid, hostAddress, portVal, tls, sni, host, encryption)
                }
            } else if (trimmed.startsWith("vmess://")) {
                val b64 = trimmed.substring(8).trim()
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
                val add = json.optString("add", "104.16.1.1")
                val port = json.optInt("port", 443)
                val id = json.optString("id", "")
                val tls = json.optString("tls", "")
                val sni = json.optString("sni", "")
                val host = json.optString("host", "")
                return ParsedProxy("vmess", id, add, port, if (tls == "tls") "tls" else null, sni.ifEmpty { null }, host.ifEmpty { null }, "none")
            }
        } catch (e: Exception) {
            // Fallback parse
        }
        return ParsedProxy("vless", "uuid-placeholder", "104.16.1.1", 443, null, null, null, null)
    }
}
