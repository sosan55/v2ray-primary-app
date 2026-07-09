package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import com.example.data.ServerEntity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class V2RayVpnService : VpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var hevTunnelThread: Thread? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        private const val CHANNEL_ID = "v2ray_vpn_service_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startVpn()
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan Core Active")
            .setContentText("Connected in real tunnel mode. Routing all apps traffic through gateway.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                repository.log("VPN", "ERROR", "Cannot start VPN: No active server selected in client database.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            repository.log("VPN", "INFO", "Connecting to node: ${server.name} (${server.address}:${server.port})")

            // 1. Establish the TUN Interface with requested routes and exclude our own package to prevent routing loop
            try {
                repository.log("TUNNEL", "INFO", "Allocating local tun0 interface file descriptor...")

                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("10.0.0.2", 24) // Internal tunnel IP
                    .addRoute("0.0.0.0", 0)    // High priority global routing
                    .addDnsServer("1.1.1.1")   // High performance Cloudflare DNS
                    .addDnsServer("8.8.8.8")   // High performance Google DNS
                    .setMtu(1400)              // Prevents fragmentation on LTE/Cellular networks

                try {
                    builder.addDisallowedApplication(packageName)
                    repository.log("TUNNEL", "INFO", "Bypassed package to avoid routing loop: $packageName")
                } catch (e: Exception) {
                    repository.log("TUNNEL", "WARNING", "Could not add disallowed application exclusion: ${e.localizedMessage}")
                }

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor != null) {
                    repository.log("TUNNEL", "SUCCESS", "Tun interface established. FD allocated successfully. Routing global out.")
                } else {
                    repository.log("TUNNEL", "ERROR", "VpnService.Builder returned null Interface. Check Android permissions.")
                }
            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "Tunnel build failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            val fdNum = interfaceDescriptor?.fd ?: -1

            // Copy routing databases (geoip.dat and geosite.dat) from assets to filesDir if present
            try {
                listOf("geoip.dat", "geosite.dat").forEach { filename ->
                    val destFile = File(filesDir, filename)
                    if (!destFile.exists() || destFile.length() == 0L) {
                        repository.log("SYSTEM", "INFO", "Copying $filename from assets to files directory...")
                        assets.open(filename).use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        repository.log("SYSTEM", "SUCCESS", "$filename copied successfully. Size: ${destFile.length()} bytes.")
                    }
                }
            } catch (e: Exception) {
                repository.log("SYSTEM", "WARNING", "Could not copy routing databases from assets: ${e.localizedMessage}")
            }

            // 2. Generate the Xray config. Xray never sees the TUN fd or has any
            // "tun" inbound — it only exposes a plain SOCKS5 inbound on loopback.
            // The TUN layer is handled separately by hev-socks5-tunnel (step 4 below),
            // once we've confirmed this SOCKS5 inbound is actually up.
            val configJson = XrayConfigGenerator.generate(server, filesDir)
            val configFile = File(cacheDir, "xray_config.json")
            try {
                configFile.writeText(configJson)
                repository.log("CONFIG", "SUCCESS", "Generated Xray config (socks-only inbound). Path: ${configFile.absolutePath}")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to cache configuration: ${e.localizedMessage}")
            }

            // 3. Discover and execute core binary process.
            // NOTE: The binary is expected to live at nativeLibraryDir/libxray.so, placed there
            // by the app's jniLibs packaging (see build.gradle.kts:downloadXrayCore task).
            // Android extracts jniLibs with execute permission at install time, which is why
            // this location works while a runtime-downloaded file placed in filesDir would not
            // (Android 10+ enforces W^X and blocks executing files written to internal storage).
            val binary = locateCoreBinary(applicationContext, repository)
            if (binary == null || !binary.exists()) {
                repository.log("XRAY-CORE", "ERROR", "Xray/V2Ray compiled binary could not be found. Expected it at nativeLibraryDir/libxray.so — check that the app was built with the Gradle downloadXrayCore task.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            try {
                repository.log("XRAY-CORE", "INFO", "Executing core binary daemon: ${binary.absolutePath}")

                val commandList = mutableListOf<String>()
                commandList.add(binary.absolutePath)
                commandList.add("-config")
                commandList.add(configFile.absolutePath)

                val processBuilder = ProcessBuilder()
                    .command(commandList)
                    .redirectErrorStream(true)

                // Set location assets directory so Xray can find geoip.dat and geosite.dat
                processBuilder.environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
                processBuilder.environment()["V2RAY_LOCATION_ASSET"] = filesDir.absolutePath

                xrayProcess = processBuilder.start()

                repository.log("XRAY-CORE", "SUCCESS", "Process spawned successfully with process ID: ${xrayProcess.hashCode()}")

                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                // Pipe stdout/stderr to repository logs on its own coroutine, so this
                // coroutine can move on to starting the tunnel layer instead of blocking
                // here until Xray exits.
                val logJob = serviceScope.launch {
                    val reader = BufferedReader(InputStreamReader(xrayProcess?.inputStream))
                    var line: String?
                    while (isActive && xrayProcess != null) {
                        line = withContext(Dispatchers.IO) { reader.readLine() }
                        if (line == null) break
                        if (line.isNotBlank()) {
                            Log.d("XRAY-CORE", line)
                            // Skip highly spammy per-packet logs to protect Room database write queue and prevent UI freeze
                            val isNoisy = line.contains("tcp:") || line.contains("udp:") || line.contains("email:") || line.contains("accepted") || line.contains("127.0.0.1:")
                            if (!isNoisy || line.contains("warning", ignoreCase = true) || line.contains("error", ignoreCase = true)) {
                                val trimmedLine = if (line.length > 200) line.take(200) + "..." else line
                                repository.log("XRAY-CORE", if (line.contains("error", ignoreCase = true)) "ERROR" else "INFO", trimmedLine)
                            }
                        }
                    }
                }

                // 4. Only once Xray's local SOCKS5 inbound is actually accepting
                // connections do we hand the TUN fd to hev-socks5-tunnel. Starting
                // the tunnel before Xray is ready would just mean every packet gets
                // dropped with connection-refused until Xray catches up.
                val socksReady = waitForSocksReady(XrayConfigGenerator.SOCKS_INBOUND_PORT)
                if (!socksReady) {
                    repository.log("XRAY-CORE", "ERROR", "Xray's SOCKS5 inbound (127.0.0.1:${XrayConfigGenerator.SOCKS_INBOUND_PORT}) never came up in time. Not starting the tunnel layer.")
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }
                    xrayProcess?.destroy()
                    stopSelf()
                    return@launch
                }

                if (fdNum != -1) {
                    val hevConfigFile = File(cacheDir, "hev_tunnel.yml")
                    HevSocks5Tunnel.writeConfig(hevConfigFile, XrayConfigGenerator.SOCKS_INBOUND_PORT)
                    repository.log("HEV-TUNNEL", "INFO", "Handing TUN fd ($fdNum) to hev-socks5-tunnel, forwarding into 127.0.0.1:${XrayConfigGenerator.SOCKS_INBOUND_PORT}")

                    hevTunnelThread = Thread({
                        val exitCode = HevSocks5Tunnel.start(hevConfigFile.absolutePath, fdNum)
                        repository.log("HEV-TUNNEL", if (exitCode == 0) "INFO" else "ERROR", "hev-socks5-tunnel loop exited with code $exitCode.")
                    }, "hev-socks5-tunnel").apply {
                        isDaemon = true
                        start()
                    }
                } else {
                    repository.log("HEV-TUNNEL", "ERROR", "No valid TUN fd available; traffic will not be routed through the tunnel.")
                }

                // Wait for Xray to exit (deliberately, via stopVpn(), or a crash) and
                // reflect that accurately in the UI state instead of silently leaving
                // it as CONNECTED while nothing is actually running.
                val exitCode = try {
                    xrayProcess?.waitFor()
                } catch (e: Exception) {
                    null
                }
                logJob.cancel()

                if (coroutineContext.isActive && xrayProcess != null) {
                    // Still considered "running" from the service's perspective, meaning this
                    // wasn't triggered by a deliberate stopVpn() call — the core process died on its own.
                    repository.log(
                        "XRAY-CORE",
                        "ERROR",
                        "Xray core process exited unexpectedly with code $exitCode. The tunnel is no longer active."
                    )
                    // With Xray gone, the SOCKS5 backend the tunnel forwards into no
                    // longer exists — stop it too instead of leaving it spinning.
                    HevSocks5Tunnel.stop()
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                        VpnCoreManager.activeVpnCoreManager?.stopTracking()
                    }
                }
            } catch (e: Exception) {
                repository.log("XRAY-CORE", "ERROR", "Execution fail: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
            }
        }
    }

    /**
     * Polls 127.0.0.1:[port] until it accepts a TCP connection or [timeoutMs]
     * elapses. Used to confirm Xray's SOCKS5 inbound is actually up before
     * handing the TUN fd to hev-socks5-tunnel.
     */
    private suspend fun waitForSocksReady(port: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (e: Exception) {
                delay(150)
            }
        }
        return false
    }

    /**
     * Locates the Xray core executable.
     *
     * The binary must live at nativeLibraryDir/libxray.so — placed there via the app's
     * jniLibs packaging (see build.gradle.kts:downloadXrayCore task, which downloads the
     * official Xray-core Android release and copies it to src/main/jniLibs/arm64-v8a/libxray.so).
     *
     * This location is required (not optional) on Android 10+: the OS enforces a W^X
     * (write XOR execute) policy that blocks executing files written to app-private
     * storage such as filesDir or cacheDir at runtime. jniLibs is the one directory
     * Android extracts with execute permission at install time via PackageManager,
     * which is why this technique — naming the executable with a .so suffix and
     * placing it in jniLibs — works, while downloading a binary to filesDir at runtime
     * and trying to execute it would silently fail.
     */
    private suspend fun locateCoreBinary(context: Context, repository: V2RayRepository): File? {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeLibDir, "libxray.so")
        if (nativeBinary.exists() && nativeBinary.length() > 1_000_000) {
            repository.log("SYSTEM", "SUCCESS", "Located standard executable library in nativeLibraryDir: ${nativeBinary.absolutePath} (${nativeBinary.length()} bytes). Running directly to satisfy Android 10+ execution policies.")
            return nativeBinary
        }

        repository.log("SYSTEM", "ERROR", "libxray.so not found (or too small) in nativeLibraryDir: ${nativeBinary.absolutePath}. This means the APK was not built correctly — check that the Gradle downloadXrayCore task ran and placed a valid binary there.")
        return null
    }

    private fun stopVpn() {
        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            repository.log("VPN", "INFO", "Disconnecting client tunnel session...")

            // Stop the tunnel loop first — it's the thing actively reading/writing
            // the TUN fd, so it needs to unwind before we close that fd or kill the
            // SOCKS5 backend it forwards into.
            try {
                HevSocks5Tunnel.stop()
                hevTunnelThread?.join(2000)
                hevTunnelThread = null
                repository.log("HEV-TUNNEL", "SUCCESS", "Tunnel loop stopped.")
            } catch (e: Exception) {
                repository.log("HEV-TUNNEL", "ERROR", "Failed to stop tunnel loop cleanly: ${e.localizedMessage}")
            }

            // Terminate background process
            try {
                xrayProcess?.destroy()
                xrayProcess = null
                repository.log("XRAY-CORE", "WARNING", "Xray daemon process killed gracefully.")
            } catch (e: Exception) {
                repository.log("XRAY-CORE", "ERROR", "Failed to destroy core daemon: ${e.localizedMessage}")
            }

            // Close tunnel interface
            try {
                interfaceDescriptor?.close()
                interfaceDescriptor = null
                repository.log("TUNNEL", "SUCCESS", "Tun interface closed. Network routes released back to system.")
            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "Failed to close Tun interface: ${e.localizedMessage}")
            }

            withContext(Dispatchers.Main) {
                VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.DISCONNECTED)
                VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
                VpnCoreManager.activeVpnCoreManager?.stopTracking()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "V2Ray Dan System Status Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }
}
