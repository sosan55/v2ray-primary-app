package com.example.service

import java.io.File

/**
 * Thin Kotlin wrapper around the native hev-socks5-tunnel library
 * (https://github.com/heiher/hev-socks5-tunnel).
 *
 * WHY THIS EXISTS:
 * Xray-core has no "tun" inbound — it only understands socks/http/vmess/
 * vless/trojan/shadowsocks/dokodemo-door. This library is the layer that
 * actually terminates the raw TUN file descriptor handed out by
 * VpnService.Builder.establish(), and forwards the IP packets into a plain
 * SOCKS5 endpoint — in our case Xray's own "socks-in" inbound on
 * 127.0.0.1:$SOCKS_INBOUND_PORT (see XrayConfigGenerator).
 *
 * REQUIRES: libhev-socks5-tunnel.so present under
 * src/main/jniLibs/<abi>/ for every ABI you ship (arm64-v8a at minimum).
 * See the Gradle `downloadHevSocks5Tunnel` task (to be added to
 * build.gradle.kts) for how that .so gets there.
 */
object HevSocks5Tunnel {

    @Volatile
    private var isRunning = false

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /**
     * Blocking call — runs the tunnel's event loop on the calling thread
     * until [stop] is invoked or the tunnel exits on its own (e.g. TUN fd
     * closed). MUST be launched on a dedicated Thread, never on a shared
     * coroutine dispatcher, or it will starve every other coroutine on
     * that dispatcher for the lifetime of the VPN session.
     *
     * @return the native exit code (0 on clean shutdown via [stop]).
     */
    private external fun nativeMainFromFile(configPath: String, tunFd: Int): Int

    /** Signals the running tunnel loop to shut down. Safe from any thread. */
    private external fun nativeQuit()

    /**
     * Starts the tunnel and blocks until it stops. Call this from a
     * dedicated Thread (see V2RayVpnService), not from a coroutine.
     */
    fun start(configPath: String, tunFd: Int): Int {
        isRunning = true
        return try {
            nativeMainFromFile(configPath, tunFd)
        } finally {
            isRunning = false
        }
    }

    /** Requests a clean shutdown of the tunnel loop, if one is running. */
    fun stop() {
        if (isRunning) {
            nativeQuit()
        }
    }

    fun isActive(): Boolean = isRunning

    /**
     * Writes the YAML config hev-socks5-tunnel expects. This is a separate
     * file from xray_config.json — the two processes/threads don't share
     * a config format.
     */
    fun writeConfig(
        destFile: File,
        socksPort: Int,
        mtu: Int = 1400
    ): File {
        destFile.writeText(
            """
            tunnel:
              name: tun0
              mtu: $mtu
              multi-queue: false
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 20480
            """.trimIndent()
        )
        return destFile
    }
}
