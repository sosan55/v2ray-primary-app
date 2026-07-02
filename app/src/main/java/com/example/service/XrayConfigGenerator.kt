package com.example.service

import com.example.data.ServerEntity

object XrayConfigGenerator {

    fun generate(server: ServerEntity, fd: Int = -1): String {
        val outbounds = when (server.type.uppercase()) {
            "VLESS" -> generateVlessOutbound(server)
            "VMESS" -> generateVmessOutbound(server)
            "TROJAN" -> generateTrojanOutbound(server)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server)
            else -> generateFreedomOutbound()
        }

        val tunInboundOpt = if (fd != -1) {
            """,
            {
              "port": 0,
              "protocol": "tun",
              "settings": {
                "name": "xray0",
                "MTU": 1500,
                "sniffing": {
                  "enabled": true,
                  "destOverride": ["http", "tls", "quic"]
                }
              }
            }"""
        } else {
            ""
        }

        // تغییر استراتژی به IPIfNonMatch برای جلوگیری از کرش در صورت عدم بارگذاری صحیح دیتابیس
        val routingRules = """
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {
                "type": "field",
                "outboundTag": "direct",
                "domain": ["geosite:ir"]
              },
              {
                "type": "field",
                "outboundTag": "direct",
                "ip": ["geoip:private", "geoip:ir"]
              }
            ]
          },
        """.trimIndent()

        return """
        {
          "log": {
            "loglevel": "info"
          },
          $routingRules
          "inbounds": [
            {
              "port": 10808,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              }
            },
            {
              "port": 10809,
              "listen": "127.0.0.1",
              "protocol": "http",
              "settings": {}
            }
            $tunInboundOpt
          ],
          "outbounds": [
            $outbounds,
            {
              "protocol": "freedom",
              "settings": {},
              "tag": "direct"
            }
          ]
        }
        """.trimIndent()
    }

    private fun generateVlessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        val flowValue = server.flow.ifEmpty {
            if (server.security.lowercase() == "reality") "xtls-rprx-vision" else ""
        }
        return """
        {
          "protocol": "vless",
          "settings": {
            "vnext": [{
              "address": "${server.address}",
              "port": ${server.port},
              "users": [{
                "id": "${server.uuid}",
                "encryption": "none",
                "flow": "$flowValue",
                "level": 0
              }]
            }]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """.trimIndent()
    }

    private fun generateVmessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "vnext": [{
              "address": "${server.address}",
              "port": ${server.port},
              "users": [{
                "id": "${server.uuid}",
                "alterId": ${server.alterId},
                "security": "${server.security.ifEmpty { "auto" }}",
                "level": 0
              }]
            }]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """.trimIndent()
    }

    private fun generateTrojanOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "trojan",
          "settings": {
            "servers": [{
              "address": "${server.address}",
              "port": ${server.port},
              "password": "${server.uuid}",
              "level": 0
            }]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """.trimIndent()
    }

    private fun generateShadowsocksOutbound(server: ServerEntity): String {
        val creds = server.uuid.split(":")
        val method = if (creds.isNotEmpty()) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else "mypassword"
        val streamSettingsJson = generateStreamSettings(server)

        return """
        {
          "protocol": "shadowsocks",
          "settings": {
            "servers": [{
              "address": "${server.address}",
              "port": ${server.port},
              "method": "$method",
              "password": "$password",
              "level": 0
            }]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """.trimIndent()
    }

    private fun generateFreedomOutbound(): String = """{"protocol": "freedom", "settings": {}, "tag": "proxy"}"""

    private fun generateStreamSettings(server: ServerEntity): String {
        val isReality = server.security.lowercase() == "reality"
        val securityStr = when {
            isReality -> "reality"
            server.tls -> "tls"
            else -> "none"
        }

        val securityConfig = when {
            isReality -> """
            "realitySettings": {
              "show": false,
              "fingerprint": "${server.fingerprint.ifEmpty { "chrome" }}",
              "serverName": "${server.sni.ifEmpty { "www.google.com" }}",
              "publicKey": "${server.publicKey}",
              "shortId": "${server.shortId}",
              "spiderX": "/"
            }""".trimIndent()
            server.tls -> """
            "tlsSettings": {
              "serverName": "${server.sni.ifEmpty { server.address }}",
              "allowInsecure": true
            }""".trimIndent()
            else -> ""
        }

        val transportConfig = when (server.network.lowercase()) {
            "ws" -> """
            "wsSettings": {
              "path": "${server.path.ifEmpty { "/" }}",
              "headers": { "Host": "${server.host.ifEmpty { server.address }}" }
            }""".trimIndent()
            "grpc" -> """
            "grpcSettings": { "serviceName": "${server.path.ifEmpty { "v2ray-grpc" }}" }""".trimIndent()
            else -> ""
        }

        val parts = mutableListOf<String>()
        if (securityConfig.isNotEmpty()) parts.add(securityConfig)
        if (transportConfig.isNotEmpty()) parts.add(transportConfig)

        return """
        {
          "network": "${server.network.lowercase().ifEmpty { "tcp" }}",
          "security": "$securityStr"
          ${if (parts.isNotEmpty()) "," else ""}
          ${parts.joinToString(",")}
        }
        """.trimIndent()
    }
}
