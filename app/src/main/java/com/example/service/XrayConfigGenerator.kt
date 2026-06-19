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
              "protocol": "tun",
              "port": 0,
              "settings": {
                "stack": "gvisor",
                "name": "tun0",
                "mtu": 1500,
                "fileDescriptor": $fd,
                "file_descriptor": $fd,
                "fd": $fd,
                "sniffing": {
                  "enabled": true,
                  "destOverride": ["http", "tls", "quic"]
                }
              }
            }"""
        } else {
            ""
        }

        return """
        {
          "log": {
            "loglevel": "info"
          },
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
        // ss stores method:password inside UUID field
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
        val securityStr = if (server.tls) "tls" else "none"
        val securityConfig = if (server.tls) {
            val sniToUse = server.sni.ifEmpty { server.address }
            """
            "tlsSettings": {
              "serverName": "$sniToUse",
              "allowInsecure": true
            }
            """
        } else ""

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
