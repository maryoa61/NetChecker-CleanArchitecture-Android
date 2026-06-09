package com.netchecker.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a Cloudflare IP address verified as responsive on port 443.
 */
@Entity(tableName = "clean_ips")
data class CleanIpEntity(
    @PrimaryKey
    val ipAddress: String,
    val pingMs: Long,
    val lastChecked: Long,
    val operatorType: String = "ALL"
)

/**
 * Captures custom proxy network configs (e.g. Vless, Trojan, Shadowsocks)
 * with updated round-trip latency attributes and network active state flags.
 */
@Entity(tableName = "proxy_configs")
data class ProxyConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val remarks: String,
    val rawConfig: String,
    val currentPing: Long,
    val isWorking: Boolean
)
