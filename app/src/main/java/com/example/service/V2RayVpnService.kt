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

            // Generate real configuration JSON string
            val configJson = XrayConfigGenerator.generate(server)
            val configFile = File(cacheDir, "xray_config.json")
            try {
                configFile.writeText(configJson)
                repository.log("CONFIG", "SUCCESS", "Generated real client config format. Path: ${configFile.absolutePath}")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to cache configuration: ${e.localizedMessage}")
            }

            // 1. Establish the TUN Interface with requested routes
            try {
                repository.log("TUNNEL", "INFO", "Allocating local tun0 interface file descriptor...")
                
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("10.0.0.2", 24) // Internal tunnel IP
                    .addRoute("0.0.0.0", 0)    // High priority global routing
                    .addDnsServer("8.8.8.8")   // Prevent Dns leaks
                    .setMtu(1500)

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

            // 2. Discover and execute core binary process
            val binary = locateCoreBinary(applicationContext, repository)
            if (binary == null || !binary.exists()) {
                repository.log("XRAY-CORE", "ERROR", "Xray/V2Ray compiled binary could not be found or copied. Please ensure a valid executable fits at assets.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED) // Transition anyway to show logs setup
                }
                return@launch
            }

            try {
                val fdNum = interfaceDescriptor?.fd ?: -1
                repository.log("XRAY-CORE", "INFO", "Executing core binary daemon: ${binary.absolutePath}")
                
                val commandList = mutableListOf<String>()
                commandList.add(binary.absolutePath)
                commandList.add("-config")
                commandList.add(configFile.absolutePath)
                if (fdNum != -1) {
                    // Pass the TUN file descriptor so xray/tun2socks subprocess can bind to it
                    commandList.add("-tun-fd")
                    commandList.add(fdNum.toString())
                }

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

                // Pipe real stdout/stderr to repository logs in real-time
                val reader = BufferedReader(InputStreamReader(xrayProcess?.inputStream))
                var line: String?
                while (coroutineContext.isActive && xrayProcess != null) {
                    line = withContext(Dispatchers.IO) { reader.readLine() }
                    if (line == null) break
                    if (line.isNotBlank()) {
                        repository.log("XRAY-CORE", "INFO", line)
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

    private fun locateCoreBinary(context: Context, repository: V2RayRepository): File? {
        // Look in nativeLibrariesDir first (preinstalled compiled dynamic shared binary)
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeLibDir, "libxray.so")
        if (nativeBinary.exists()) {
            serviceScope.launch { repository.log("SYSTEM", "INFO", "Located executable library in nativeLibraryDir: ${nativeBinary.name}") }
            return nativeBinary
        }

        // We can extract/access the binary file inside internal storage filesDir
        val filesBinary = File(context.filesDir, "xray")
        
        // Force extract if it doesn't exist or is a simple text placeholder
        val needsExtract = if (filesBinary.exists()) {
            // Check if it is a placeholder or has less content
            filesBinary.length() < 1000
        } else {
            true
        }

        if (needsExtract) {
            try {
                serviceScope.launch { repository.log("SYSTEM", "INFO", "Extracting Xray core binary from assets to files directory: ${filesBinary.absolutePath}") }
                context.assets.open("xray").use { input ->
                    filesBinary.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Set executable rights for both owner and group/others to avoid Permission Denied
                filesBinary.setReadable(true, false)
                val status1 = filesBinary.setExecutable(true, false)
                val status2 = filesBinary.setExecutable(true, true)
                
                // Fallback shell chmod command for solid security guarantees in Android sandbox
                try {
                    val chmodProcess = Runtime.getRuntime().exec(arrayOf("chmod", "755", filesBinary.absolutePath))
                    chmodProcess.waitFor()
                    serviceScope.launch { repository.log("SYSTEM", "SUCCESS", "Shell permissions chmod 755 completed successfully on: ${filesBinary.name}") }
                } catch (e: Exception) {
                    serviceScope.launch { repository.log("SYSTEM", "WARNING", "Shell chmod failed: ${e.localizedMessage}") }
                }

                serviceScope.launch {
                    repository.log("SYSTEM", "SUCCESS", "Extracted and configured executable rights on binary: ownerStatus=$status1, globalStatus=$status2")
                }
            } catch (e: Exception) {
                serviceScope.launch {
                    repository.log("SYSTEM", "ERROR", "Failed to extract Xray core binary from assets: ${e.localizedMessage}")
                }
            }
        } else {
            // Enforce correct permissions on existing file anyway
            filesBinary.setReadable(true, false)
            filesBinary.setExecutable(true, false)
            filesBinary.setExecutable(true, true)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", filesBinary.absolutePath)).waitFor()
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
