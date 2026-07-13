package com.example.service

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
 * This is built automatically by CMake (see app/src/main/cpp/CMakeLists.txt)
 * during the Android build process.
 */
object HevSocks5Tunnel {

    // Was a plain @Volatile Boolean before. That's fine for visibility but
    // gives no atomicity: two threads could both read isRunning == true and
    // both proceed to call nativeQuit(), which is exactly the double-call
    // that was corrupting native state and crashing the process. isRunning
    // now also doubles as "is a session currently open", and stopRequested
    // guarantees nativeQuit() fires at most once per start()/stop() cycle
    // even under concurrent callers.
    private val isRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var libraryLoaded = false

    private var libraryLoadError: Throwable? = null

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libraryLoaded = true
            Log.d("HEV-TUNNEL", "✓ libhev-socks5-tunnel.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            // Most likely cause: libhev-socks5-tunnel.so isn't present under
            // jniLibs/<abi>/ for this ABI — e.g. the CMake build hasn't run
            // or failed, or the library wasn't compiled for this device's ABI.
            // Swallow it here so referencing this object doesn't crash the whole app;
            // start() will instead throw a clear, catchable IllegalStateException below.
            libraryLoadError = e
            Log.e("HEV-TUNNEL", "✗ Failed to load libhev-socks5-tunnel.so: ${e.message}")
        }
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
        if (!libraryLoaded) {
            val errorMsg = "libhev-socks5-tunnel.so failed to load (${libraryLoadError?.message}). " +
                "Check that it's bundled under jniLibs/<abi>/ for this device's ABI. " +
                "The library should be built automatically by CMake (app/src/main/cpp/CMakeLists.txt). " +
                "If this error persists, try: 1) Clean rebuild (./gradlew clean build), " +
                "2) Verify NDK is installed (ndkVersion 27.0.12077973), " +
                "3) Check that CMake build succeeded in logs."
            Log.e("HEV-TUNNEL", errorMsg)
            throw IllegalStateException(errorMsg, libraryLoadError)
        }
        stopRequested.set(false)
        isRunning.set(true)
        Log.i("HEV-TUNNEL", "Starting tunnel: config=$configPath tunFd=$tunFd")
        return try {
            nativeMainFromFile(configPath, tunFd)
        } finally {
            isRunning.set(false)
            Log.i("HEV-TUNNEL", "Tunnel event loop exited")
        }
    }

    /**
     * Requests a clean shutdown of the tunnel loop, if one is running.
     * Safe to call multiple times and/or concurrently from multiple
     * threads/coroutines — only the first call in a given session actually
     * reaches the native layer; every other call becomes a no-op.
     */
    fun stop() {
        if (isRunning.get() && stopRequested.compareAndSet(false, true)) {
            Log.i("HEV-TUNNEL", "Requesting tunnel shutdown")
            nativeQuit()
        }
    }

    fun isActive(): Boolean = isRunning.get()

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
        Log.d("HEV-TUNNEL", "Config written: ${destFile.absolutePath}")
        return destFile
    }
}
