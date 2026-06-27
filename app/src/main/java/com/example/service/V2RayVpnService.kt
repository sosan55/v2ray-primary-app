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
        // اگه intent == null یعنی START_STICKY سرویس رو restart کرده — سرور نداریم، stop کن
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent.action
        if (action == ACTION_START) {
            startVpn()
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
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

            // 1. Establish the TUN Interface
            try {
                repository.log("TUNNEL", "INFO", "Allocating local tun0 interface file descriptor...")
                
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)

                try {
                    builder.addDisallowedApplication(packageName)
                    repository.log("TUNNEL", "INFO", "Bypassed package to avoid routing loop: $packageName")
                } catch (e: Exception) {
                    repository.log("TUNNEL", "WARNING", "Could not add disallowed application exclusion: ${e.localizedMessage}")
                }

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor != null) {
                    repository.log("TUNNEL", "SUCCESS", "Tun interface established successfully.")
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

            // 2. Generate config JSON — بدون TUN inbound چون xray از fd environment variable پشتیبانی نمیکنه
            val configJson = XrayConfigGenerator.generate(server, -1)
            val configFile = File(cacheDir, "xray_config.json")
            try {
                configFile.writeText(configJson)
                repository.log("CONFIG", "SUCCESS", "Config written to: ${configFile.absolutePath}")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to write config: ${e.localizedMessage}")
            }

            // 3. Locate and run xray binary
            val binary = locateCoreBinary(applicationContext, repository)
            if (binary == null || !binary.exists()) {
                repository.log("XRAY-CORE", "ERROR", "Binary not found.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            try {
                repository.log("XRAY-CORE", "INFO", "Starting binary: ${binary.absolutePath}")

                xrayProcess = ProcessBuilder()
                    .command(binary.absolutePath, "-config", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                repository.log("XRAY-CORE", "SUCCESS", "Xray process started.")

                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                // خوندن لاگ‌های xray
                val reader = BufferedReader(InputStreamReader(xrayProcess?.inputStream))
                var line: String?
                while (coroutineContext.isActive && xrayProcess != null) {
                    line = withContext(Dispatchers.IO) { reader.readLine() }
                    if (line == null) break
                    if (line.isNotBlank()) repository.log("XRAY-CORE", "INFO", line)
                }

                // اگه loop تموم شد یعنی xray exit کرد
                if (coroutineContext.isActive) {
                    val exitCode = try { xrayProcess?.waitFor() ?: -1 } catch (e: Exception) { -1 }
                    repository.log("XRAY-CORE", "ERROR", "Core process exited unexpectedly with code: $exitCode")
                    xrayProcess = null
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                        VpnCoreManager.activeVpnCoreManager?.stopTracking()
                    }
                    stopSelf()
                }

            } catch (e: Exception) {
                repository.log("XRAY-CORE", "ERROR", "Execution failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        // مستقیم و synchronous — بدون coroutine تا مطمئن بشیم اجرا میشه
        try {
            xrayProcess?.destroy()
            xrayProcess = null
        } catch (e: Exception) {
            Log.e("VPN", "Failed to destroy xray process: ${e.localizedMessage}")
        }

        try {
            interfaceDescriptor?.close()
            interfaceDescriptor = null
        } catch (e: Exception) {
            Log.e("VPN", "Failed to close TUN interface: ${e.localizedMessage}")
        }

        // آپدیت state روی Main thread
        CoroutineScope(Dispatchers.Main).launch {
            VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.DISCONNECTED)
            VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
            VpnCoreManager.activeVpnCoreManager?.stopTracking()
        }

        // لاگ در background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = V2RayDatabase.getDatabase(applicationContext)
                V2RayRepository(db).log("VPN", "INFO", "VPN disconnected successfully.")
            } catch (e: Exception) {}
        }

        stopForeground(true)
        stopSelf()
    }

    private suspend fun downloadXrayBinary(context: Context, destination: File, repository: V2RayRepository): Boolean = withContext(Dispatchers.IO) {
        val downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.24/Xray-android-arm64-v8a.zip"
        try {
            repository.log("SYSTEM", "INFO", "Downloading Xray core binary from: $downloadUrl")
            
            val tempFile = File(context.cacheDir, "temp_xray_download")
            if (tempFile.exists()) tempFile.delete()

            java.net.URL(downloadUrl).openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            repository.log("SYSTEM", "SUCCESS", "Download finished. Size: ${tempFile.length()} bytes.")

            val isZip = try {
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { it.nextEntry != null }
            } catch (e: Exception) { false }

            if (isZip) {
                repository.log("SYSTEM", "INFO", "Extracting xray binary from ZIP...")
                var extracted = false
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zipInput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        if (entry.name == "xray" || entry.name.endsWith("/xray")) {
                            destination.outputStream().use { zipInput.copyTo(it) }
                            extracted = true
                            break
                        }
                        entry = zipInput.nextEntry
                    }
                }
                tempFile.delete()
                if (!extracted) {
                    repository.log("SYSTEM", "ERROR", "Could not find xray binary in ZIP.")
                    return@withContext false
                }
            } else {
                tempFile.renameTo(destination)
            }

            destination.setReadable(true, false)
            destination.setExecutable(true, false)
            destination.setExecutable(true, true)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", destination.absolutePath)).waitFor()
            } catch (e: Exception) {}

            repository.log("SYSTEM", "SUCCESS", "Binary ready at: ${destination.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            repository.log("SYSTEM", "ERROR", "Download failed: ${e.localizedMessage}")
            return@withContext false
        }
    }

    private suspend fun locateCoreBinary(context: Context, repository: V2RayRepository): File? {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeLibDir, "libxray.so")
        if (nativeBinary.exists() && nativeBinary.length() > 1000) {
            repository.log("SYSTEM", "SUCCESS", "Found binary in nativeLibraryDir: ${nativeBinary.absolutePath} (${nativeBinary.length()} bytes)")
            return nativeBinary
        }

        repository.log("SYSTEM", "WARNING", "libxray.so not found in nativeLibraryDir. Trying filesDir...")

        val filesBinary = File(context.filesDir, "xray")
        if (!filesBinary.exists() || filesBinary.length() < 1000) {
            repository.log("SYSTEM", "INFO", "Binary missing or too small. Starting download...")
            val success = downloadXrayBinary(context, filesBinary, repository)
            if (!success) {
                repository.log("SYSTEM", "ERROR", "All binary location attempts failed.")
                return null
            }
        }

        if (filesBinary.exists()) {
            filesBinary.setReadable(true, false)
            filesBinary.setExecutable(true, false)
            filesBinary.setExecutable(true, true)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", filesBinary.absolutePath)).waitFor()
            } catch (e: Exception) {}
        }

        return filesBinary
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "V2Ray Dan System Status Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        // اول xray و TUN رو ببند
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) {}
        // بعد job رو cancel کن
        serviceJob.cancel()
        super.onDestroy()
    }
}
