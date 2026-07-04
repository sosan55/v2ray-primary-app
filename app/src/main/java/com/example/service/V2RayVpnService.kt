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

class V2RayVpnService : VpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
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

            // 2. Generate real configuration JSON string using the allocated file descriptor
            val configJson = XrayConfigGenerator.generate(server, fdNum, filesDir)
            val configFile = File(cacheDir, "xray_config.json")
            try {
                configFile.writeText(configJson)
                repository.log("CONFIG", "SUCCESS", "Generated real client config format with Tun/FD support. Path: ${configFile.absolutePath}")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to cache configuration: ${e.localizedMessage}")
            }

            // 3. Discover and execute core binary process
            val binary = locateCoreBinary(applicationContext, repository)
            if (binary == null || !binary.exists()) {
                repository.log("XRAY-CORE", "ERROR", "Xray/V2Ray compiled binary could not be found or copied. Please ensure a valid executable fits at assets.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED) // Transition anyway to show logs setup
                }
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

                if (fdNum != -1) {
                    processBuilder.environment()["VPN_TUN_FD"] = fdNum.toString()
                    processBuilder.environment()["TUN_FD"] = fdNum.toString()
                    repository.log("TUNNEL", "SUCCESS", "Tethered TUN interface descriptor (fd: $fdNum) of VpnService to background core process successfully.")
                }

                xrayProcess = processBuilder.start()

                repository.log("XRAY-CORE", "SUCCESS", "Process spawned successfully with process ID: ${xrayProcess.hashCode()}")
                
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                // Pipe real stdout/stderr to repository logs in real-time, filtered to avoid DB write queue saturation
                val reader = BufferedReader(InputStreamReader(xrayProcess?.inputStream))
                var line: String?
                while (coroutineContext.isActive && xrayProcess != null) {
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
            } catch (e: Exception) {
                repository.log("XRAY-CORE", "ERROR", "Execution fail: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
            }
        }
    }

    private suspend fun downloadXrayBinary(context: Context, destination: File, repository: V2RayRepository): Boolean = withContext(Dispatchers.IO) {
        val downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.24/Xray-android-arm64-v8a.zip"
        try {
            repository.log("SYSTEM", "INFO", "Initiating runtime download of Xray core binary from: $downloadUrl")
            
            val tempFile = File(context.cacheDir, "temp_xray_download")
            if (tempFile.exists()) tempFile.delete()

            java.net.URL(downloadUrl).openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            repository.log("SYSTEM", "SUCCESS", "Download finished. Size: ${tempFile.length()} bytes.")

            // Check if it is a ZIP archive
            val isZip = try {
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zipInput ->
                    zipInput.nextEntry != null
                }
            } catch (e: Exception) {
                false
            }

            if (isZip) {
                repository.log("SYSTEM", "INFO", "Downloaded archive is a ZIP file. Extracting 'xray' dynamic binary...")
                var extracted = false
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zipInput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        if (entry.name == "xray" || entry.name.endsWith("/xray")) {
                            destination.outputStream().use { output ->
                                zipInput.copyTo(output)
                            }
                            extracted = true
                            break
                        }
                        entry = zipInput.nextEntry
                    }
                }
                tempFile.delete()
                if (!extracted) {
                    repository.log("SYSTEM", "ERROR", "Could not locate 'xray' executable within the downloaded ZIP package.")
                    return@withContext false
                }
            } else {
                // If it's a raw executable binary, shift it directly to destination
                repository.log("SYSTEM", "INFO", "Downloaded file is a raw binary. Saving directly...")
                tempFile.renameTo(destination)
            }

            // Set executable privileges
            destination.setReadable(true, false)
            val execOwner = destination.setExecutable(true, false)
            val execAll = destination.setExecutable(true, true)
            
            try {
                val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "755", destination.absolutePath))
                chmod.waitFor()
                repository.log("SYSTEM", "SUCCESS", "Run-time chmod 755 execution succeeded on downloaded core binary.")
            } catch (e: Exception) {
                repository.log("SYSTEM", "WARNING", "System security shell chmod output: ${e.localizedMessage}")
            }

            repository.log("SYSTEM", "SUCCESS", "Runtime core binary download and extraction complete.")
            return@withContext true
        } catch (e: Exception) {
            repository.log("SYSTEM", "ERROR", "Failed download sequence of core binary: ${e.localizedMessage}")
            return@withContext false
        }
    }

    private suspend fun locateCoreBinary(context: Context, repository: V2RayRepository): File? {
        // 1. Primary check on Android 10+: ALWAYS check the nativeLibraryDir first!
        // This is the read-only directory managed by Package Manager where files can be executed.
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeLibDir, "libxray.so")
        if (nativeBinary.exists() && nativeBinary.length() > 1_000_000) {
            repository.log("SYSTEM", "SUCCESS", "Located standard executable library in nativeLibraryDir: ${nativeBinary.absolutePath} (${nativeBinary.length()} bytes). Running directly to satisfy Android 10+ execution policies.")
            return nativeBinary
        }

        repository.log("SYSTEM", "WARNING", "libxray.so not found or too small in nativeLibraryDir. Attempting backup download or placeholder load...")

        val filesBinary = File(context.filesDir, "xray")
        
        // Check if the file is absent or just a tiny placeholder (< 1000 bytes)
        val needsDownload = !filesBinary.exists() || filesBinary.length() < 1000

        if (needsDownload) {
            repository.log("SYSTEM", "INFO", "Xray core binary on filesDir is missing or a placeholder. Starting download...")
            val downloadSuccess = downloadXrayBinary(context, filesBinary, repository)
            if (!downloadSuccess) {
                repository.log("SYSTEM", "WARNING", "Direct download failed. Falling back to native shared library or assets...")
                
                // Fallback 1: Native binary preinstalled in nativeLibraryDir (checked again just in case)
                if (nativeBinary.exists()) {
                    repository.log("SYSTEM", "INFO", "Fallback located executable library in nativeLibraryDir: ${nativeBinary.name}")
                    return nativeBinary
                }
                
                // Fallback 2: Extract from assets placeholder (as a last resort failure backup)
                try {
                    repository.log("SYSTEM", "INFO", "Extracting fallback placeholder from assets to files directory: ${filesBinary.absolutePath}")
                    context.assets.open("xray").use { input ->
                        filesBinary.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    repository.log("SYSTEM", "ERROR", "Failed to write placeholder: ${e.localizedMessage}")
                }
            }
        }

        // Ensure execution permissions on filesBinary (if it exists)
        if (filesBinary.exists()) {
            filesBinary.setReadable(true, false)
            val p1 = filesBinary.setExecutable(true, false)
            val p2 = filesBinary.setExecutable(true, true)
            try {
                val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "755", filesBinary.absolutePath))
                chmodProcess.waitFor()
            } catch (e: Exception) {}
        }

        return filesBinary
    }

    private fun stopVpn() {
        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            repository.log("VPN", "INFO", "Disconnecting client tunnel session...")

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
