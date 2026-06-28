package com.example.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // VMESS, VLESS, SHADOWSOCKS, TROJAN
    val address: String,
    val port: Int,
    val uuid: String,
    val alterId: Int = 0,
    val security: String = "auto", // VMESS: encryption method ("auto","aes-128-gcm",...) | VLESS: security type ("none","tls","reality")
    val network: String = "tcp", // tcp, ws, grpc, h2
    val path: String = "",
    val host: String = "",
    val sni: String = "",
    val tls: Boolean = false,
    val ping: Int? = null,
    val isSelected: Boolean = false,
    // --- REALITY-specific fields ---
    val flow: String = "",        // مثلاً "xtls-rprx-vision"
    val publicKey: String = "",   // پارامتر pbk
    val shortId: String = "",     // پارامتر sid
    val fingerprint: String = ""  // پارامتر fp (مثلاً "chrome")
) {
    fun toLink(): String {
        return when (type.uppercase()) {
            "VMESS" -> "vmess://$address:$port?network=$network&path=$path&host=$host&tls=$tls"
            "VLESS" -> {
                if (security.lowercase() == "reality") {
                    "vless://$uuid@$address:$port?security=reality&flow=$flow&pbk=$publicKey&sid=$shortId&fp=$fingerprint&sni=$sni&type=$network"
                } else {
                    "vless://$uuid@$address:$port?network=$network&path=$path&host=$host&security=${if (tls) "tls" else "none"}&sni=$sni"
                }
            }
            "SHADOWSOCKS" -> "ss://$uuid@$address:$port"
            "TROJAN" -> "trojan://$uuid@$address:$port?sni=$sni&tls=$tls"
            else -> "$name ($address:$port)"
        }
    }
}
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String = "V2RAY",
    val level: String = "INFO", // INFO, WARNING, ERROR, SUCCESS
    val message: String
)
