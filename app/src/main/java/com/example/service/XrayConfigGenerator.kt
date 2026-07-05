package com.example.service

import com.example.data.ServerEntity

object XrayConfigGenerator {

    fun generate(server: ServerEntity, fd: Int = -1): String {
        val outbounds = when (server.type.uppercase()) {
            "VLESS"       -> generateVlessOutbound(server)
            "VMESS"       -> generateVmessOutbound(server)
            "TROJAN"      -> generateTrojanOutbound(server)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server)
            else          -> generateFreedomOutbound()
        }

        val tunInbound = if (fd != -1) {
            """
            {
              "protocol": "tun",
              "settings": {
                "name": "tun0",
                "mtu": 1500,
                "gateway": ["10.0.0.1/24"]
              }
            }"""
        } else ""

        val inboundsSeparator = if (fd != -1) "," else ""

        // کد اصلاح شده مسیریابی که خطا نمی‌دهد
        val routing = """
        {
          "domainStrategy": "IPIfNonMatch",
          "rules": [
            {
              "type": "field",
              "ip": [
                "0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "192.168.0.0/16"
              ],
              "outboundTag": "direct"
            }
          ]
        }
        """.trimIndent()

        return """
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true }
    },
    {
      "port": 10809, "listen": "127.0.0.1", "protocol": "http", "settings": {}
    }$inboundsSeparator
    $tunInbound
  ],
  "outbounds": [
    $outbounds,
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ],
  "routing": $routing
}
        """.trimIndent()
    }

    // متدهای تولید کانفیگ (Vless, Vmess, و غیره) دست‌نخورده باقی ماندند
    private fun generateVlessOutbound(server: ServerEntity): String {
        val streamSettings = generateStreamSettings(server)
        return """
        {
          "protocol": "vless",
          "settings": {
            "vnext": [{ "address": "${server.address}", "port": ${server.port}, "users": [{ "id": "${server.uuid}", "encryption": "none", "level": 0 }] }]
          },
          "streamSettings": $streamSettings,
          "tag": "proxy"
        }""".trimIndent()
    }

    private fun generateVmessOutbound(server: ServerEntity): String {
        val streamSettings = generateStreamSettings(server)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "vnext": [{ "address": "${server.address}", "port": ${server.port}, "users": [{ "id": "${server.uuid}", "alterId": ${server.alterId}, "security": "${server.security.ifEmpty { "auto" }}", "level": 0 }] }]
          },
          "streamSettings": $streamSettings,
          "tag": "proxy"
        }""".trimIndent()
    }

    private fun generateTrojanOutbound(server: ServerEntity): String {
        val streamSettings = generateStreamSettings(server)
        return """
        {
          "protocol": "trojan",
          "settings": {
            "servers": [{ "address": "${server.address}", "port": ${server.port}, "password": "${server.uuid}", "level": 0 }]
          },
          "streamSettings": $streamSettings,
          "tag": "proxy"
        }""".trimIndent()
    }

    private fun generateShadowsocksOutbound(server: ServerEntity): String {
        val creds = server.uuid.split(":")
        val method = if (creds.isNotEmpty()) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else ""
        val streamSettings = generateStreamSettings(server)
        return """
        {
          "protocol": "shadowsocks",
          "settings": {
            "servers": [{ "address": "${server.address}", "port": ${server.port}, "method": "$method", "password": "$password", "level": 0 }]
          },
          "streamSettings": $streamSettings,
          "tag": "proxy"
        }""".trimIndent()
    }

    private fun generateFreedomOutbound(): String {
        return """{ "protocol": "freedom", "tag": "proxy" }""".trimIndent()
    }

    private fun generateStreamSettings(server: ServerEntity): String {
        val security = if (server.tls) "tls" else "none"
        val parts = mutableListOf<String>()
        if (server.tls) {
            val sni = server.sni.ifEmpty { server.address }
            parts.add("\"tlsSettings\": { \"serverName\": \"$sni\", \"allowInsecure\": false }")
        }
        when (server.network.lowercase()) {
            "ws" -> parts.add("\"wsSettings\": { \"path\": \"${server.path.ifEmpty { "/" }}\", \"headers\": { \"Host\": \"${server.host.ifEmpty { server.address }}\" } }")
            "grpc" -> parts.add("\"grpcSettings\": { \"serviceName\": \"${server.path.ifEmpty { "grpc" }}\" }")
        }
        val extra = if (parts.isNotEmpty()) ",\n" + parts.joinToString(",\n") else ""
        return """{ "network": "${server.network.lowercase().ifEmpty { "tcp" }}", "security": "$security"$extra }""".trimIndent()
    }
}
