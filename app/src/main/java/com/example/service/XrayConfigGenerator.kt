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

        // طبق مستندات رسمی xray-core (proxy/tun/README.md):
        // - این inbound روی هیچ پورتی listen نمی‌کنه (port باید 0 باشه)
        // - فقط "name" و "MTU" لازمه
        // - fd از طریق env var "xray.tun.fd" که خودِ StartLoop ست می‌کنه به xray میرسه
        //   (نیازی به فرستادن fd داخل JSON نیست)
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

        // 👈 بخش Routing اضافه شد برای دور زدن ترافیک داخلی و ایران
        val routingRules = """
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {
                "type": "field",
                "outboundTag": "direct",
                "domain": [
                  "geosite:ir"
                ]
              },
              {
                "type": "field",
                "outboundTag": "direct",
                "ip": [
                  "geoip:private",
                  "geoip:ir"
                ]
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
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "encryption": "none",
                    "flow": "$flowValue",
                    "level": 0
                  }
                ]
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateVmessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "alterId": ${server.alterId},
                    "security": "${server.security.ifEmpty { "auto" }}",
                    "level": 0
                  }
                ]
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateTrojanOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "trojan",
          "settings": {
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "password": "${server.uuid}",
                "level": 0
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateShadowsocksOutbound(server: ServerEntity): String {
        val creds = server.uuid.split(":")
        val method = if (creds.size > 0) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else "mypassword"
        val streamSettingsJson = generateStreamSettings(server)

        return """
        {
          "protocol": "shadowsocks",
          "settings": {
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "method": "$method",
                "password": "$password",
                "level": 0
              }
            ]
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateFreedomOutbound(): String {
        return """
        {
          "protocol": "freedom",
          "settings": {},
          "tag": "proxy"
        }
        """
    }

    private fun generateStreamSettings(server: ServerEntity): String {
        // تشخیص REALITY: فیلد security برابر "reality" باشه
        val isReality = server.security.lowercase() == "reality"

        val securityStr = when {
            isReality -> "reality"
            server.tls  -> "tls"
            else        -> "none"
        }

        val securityConfig = when {
            isReality -> {
                // فیلدهای واقعی REALITY که الان از پارسر می‌آن:
                //   server.sni         → serverName هدف (مثلاً "www.google.com")
                //   server.publicKey   → publicKey سرور (پارامتر pbk)
                //   server.shortId     → shortId (پارامتر sid)
                //   server.fingerprint → fingerprint مرورگر (پارامتر fp)
                val sniToUse      = server.sni.ifEmpty { "www.google.com" }
                val publicKey     = server.publicKey
                val shortId       = server.shortId
                val fingerprint   = server.fingerprint.ifEmpty { "chrome" }
                """
            "realitySettings": {
              "show": false,
              "fingerprint": "$fingerprint",
              "serverName": "$sniToUse",
              "publicKey": "$publicKey",
              "shortId": "$shortId",
              "spiderX": "/"
            }
                """
            }
            server.tls -> {
                val sniToUse = server.sni.ifEmpty { server.address }
                """
            "tlsSettings": {
              "serverName": "$sniToUse",
              "allowInsecure": true
            }
                """
            }
            else -> ""
        }

        val transportConfig = when (server.network.lowercase()) {
            "ws" -> """
            "wsSettings": {
              "path": "${server.path.ifEmpty { "/" }}",
              "headers": {
                "Host": "${server.host.ifEmpty { server.address }}"
              }
            }
            """
            "grpc" -> """
            "grpcSettings": {
              "serviceName": "${server.path.ifEmpty { "v2ray-grpc" }}"
            }
            """
            else -> ""
        }

        val separator = if (securityConfig.isNotEmpty() && transportConfig.isNotEmpty()) "," else ""

        return """
        {
          "network": "${server.network.lowercase().ifEmpty { "tcp" }}",
          "security": "$securityStr"
          ${if (securityConfig.isNotEmpty() || transportConfig.isNotEmpty()) "," else ""}
          $securityConfig
          $separator
          $transportConfig
        }
        """
    }
}
