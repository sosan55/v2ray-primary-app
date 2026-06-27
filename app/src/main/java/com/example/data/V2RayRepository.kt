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
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class V2RayVpnService : VpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP  = "com.example.service.STOP"
        private const val CHANNEL_ID       = "v2ray_vpn_service_channel"
        private const val NOTIFICATION_ID  = 1002
        private const val SOCKS_PORT       = 10808
        private const val TUN_ADDR         = "10.0.0.2"
        private const val TUN_PREFIX       = 24
        private const val TUN_MTU          = 1500
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        createNotificationChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan")
            .setContentText("Connecting...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        )

        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                repository.log("VPN", "ERROR", "No server selected.")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf(); return@launch
            }

            repository.log("VPN", "INFO", "Connecting to: ${server.name} (${server.address}:${server.port})")

            // ── 1. TUN interface ──────────────────────────────────────────
            val fd: Int
            try {
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress(TUN_ADDR, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .setMtu(TUN_MTU)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor == null) {
                    repository.log("TUNNEL", "ERROR", "establish() returned null — check VPN permission.")
                    withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                    stopSelf(); return@launch
                }
                fd = interfaceDescriptor!!.fd
                repository.log("TUNNEL", "SUCCESS", "TUN interface established. fd=$fd")
            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "TUN build failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf(); return@launch
            }

            // ── 2. xray config ────────────────────────────────────────────
            val configFile = File(cacheDir, "xray_config.json")
            try {
                // fd=-1 چون xray به عنوان SOCKS proxy کار میکنه، TUN رو tun2socks handle میکنه
                configFile.writeText(XrayConfigGenerator.generate(server, -1))
                repository.log("CONFIG", "SUCCESS", "Config written.")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to write config: ${e.localizedMessage}")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf(); return@launch
            }

            // ── 3. xray binary ────────────────────────────────────────────
            val xrayBinary = locateBinary(applicationContext, repository, "libxray.so", "xray",
                "https://github.com/XTLS/Xray-core/releases/download/v1.8.24/Xray-android-arm64-v8a.zip", "xray")
            if (xrayBinary == null) {
                repository.log("XRAY-CORE", "ERROR", "xray binary not found.")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf(); return@launch
            }

            // ── 4. tun2socks binary ───────────────────────────────────────
            val tun2socksBinary = locateBinary(applicationContext, repository, "libtun2socks.so", "tun2socks",
                "https://github.com/xjasonlyu/tun2socks/releases/download/v2.5.2/tun2socks-android-arm64.zip", "tun2socks")
            if (tun2socksBinary == null) {
                repository.log("TUN2SOCKS", "ERROR", "tun2socks binary not found.")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf(); return@launch
            }

            // ── 5. xray رو start کن ──────────────────────────────────────
            try {
                repository.log("XRAY-CORE", "INFO", "Starting xray...")
                xrayProcess = ProcessBuilder()
                    .command(xrayBinary.absolutePath, "-config", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                // منتظر میمونیم xray آماده بشه (لاگ "started" بده)
                var xrayReady = false
                val xrayReader = BufferedReader(InputStreamReader(xrayProcess!!.inputStream))
                val xrayLogJob = launch {
                    var line: String?
                    while (isActive) {
                        line = withContext(Dispatchers.IO) { xrayReader.readLine() }
                        if (line == null) break
                        if (line.isNotBlank()) repository.log("XRAY-CORE", "INFO", line)
                        if (!xrayReady && line.contains("started", ignoreCase = true)) {
                            xrayReady = true
                        }
                    }
                }

                // حداکثر ۵ ثانیه صبر میکنیم xray ready بشه
                var waited = 0
                while (!xrayReady && waited < 50) {
                    delay(100)
                    waited++
                }

                if (!xrayReady) {
                    repository.log("XRAY-CORE", "ERROR", "xray did not start within 5 seconds.")
                    xrayLogJob.cancel()
                    withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                    stopVpn(); return@launch
                }

                repository.log("XRAY-CORE", "SUCCESS", "xray is ready on SOCKS port $SOCKS_PORT.")

                // ── 6. tun2socks رو start کن ─────────────────────────────
                // tun2socks ترافیک TUN رو میگیره و به xray SOCKS میفرسته
                repository.log("TUN2SOCKS", "INFO", "Starting tun2socks (fd=$fd → socks5://127.0.0.1:$SOCKS_PORT)...")
                tun2socksProcess = ProcessBuilder()
                    .command(
                        tun2socksBinary.absolutePath,
                        "-device", "fd:///proc/self/fd/$fd",
                        "-proxy", "socks5://127.0.0.1:$SOCKS_PORT",
                        "-loglevel", "info"
                    )
                    .redirectErrorStream(true)
                    .start()

                // لاگ tun2socks
                val t2sReader = BufferedReader(InputStreamReader(tun2socksProcess!!.inputStream))
                var t2sReady = false
                val tun2socksLogJob = launch {
                    var line: String?
                    while (isActive) {
                        line = withContext(Dispatchers.IO) { t2sReader.readLine() }
                        if (line == null) break
                        if (line.isNotBlank()) repository.log("TUN2SOCKS", "INFO", line)
                        if (!t2sReady && (
                            line.contains("started", ignoreCase = true) ||
                            line.contains("running", ignoreCase = true) ||
                            line.contains("tun2socks", ignoreCase = true)
                        )) {
                            t2sReady = true
                        }
                    }
                }

                // حداکثر ۳ ثانیه صبر برای tun2socks
                waited = 0
                while (!t2sReady && waited < 30) {
                    delay(100)
                    waited++
                }

                repository.log("TUN2SOCKS", "SUCCESS", "tun2socks running. Traffic is now routed through xray.")

                // ── 7. همه چیز آماده — CONNECTED ─────────────────────────
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                // منتظر بمون تا یکی از پروسه‌ها exit کنه
                xrayLogJob.join()

                // اگه اینجا رسیدیم یعنی xray exit کرد
                if (coroutineContext.isActive) {
                    val code = try { xrayProcess?.waitFor() ?: -1 } catch (e: Exception) { -1 }
                    repository.log("XRAY-CORE", "ERROR", "xray exited with code: $code")
                    tun2socksLogJob.cancel()
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                        VpnCoreManager.activeVpnCoreManager?.stopTracking()
                    }
                    stopSelf()
                }

            } catch (e: Exception) {
                repository.log("VPN", "ERROR", "Startup failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) { VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR) }
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        try { tun2socksProcess?.destroy(); tun2socksProcess = null } catch (e: Exception) { Log.e("VPN", "tun2socks destroy: ${e.localizedMessage}") }
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) { Log.e("VPN", "xray destroy: ${e.localizedMessage}") }
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) { Log.e("VPN", "TUN close: ${e.localizedMessage}") }

        CoroutineScope(Dispatchers.Main).launch {
            VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.DISCONNECTED)
            VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
            VpnCoreManager.activeVpnCoreManager?.stopTracking()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = V2RayDatabase.getDatabase(applicationContext)
                V2RayRepository(db).log("VPN", "INFO", "VPN disconnected.")
            } catch (e: Exception) {}
        }
        stopForeground(true)
        stopSelf()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private suspend fun locateBinary(
        context: Context,
        repository: V2RayRepository,
        soName: String,
        fileName: String,
        downloadUrl: String,
        zipEntry: String
    ): File? {
        // اول nativeLibraryDir
        val native = File(context.applicationInfo.nativeLibraryDir, soName)
        if (native.exists() && native.length() > 1000) {
            repository.log("SYSTEM", "SUCCESS", "$soName found in nativeLibraryDir (${native.length()} bytes)")
            return native
        }

        // بعد filesDir
        val local = File(context.filesDir, fileName)
        if (!local.exists() || local.length() < 1000) {
            repository.log("SYSTEM", "INFO", "$fileName not found. Downloading...")
            val ok = downloadBinary(context, repository, downloadUrl, zipEntry, local)
            if (!ok) { repository.log("SYSTEM", "ERROR", "Failed to obtain $fileName."); return null }
        }

        local.setReadable(true, false)
        local.setExecutable(true, false)
        local.setExecutable(true, true)
        try { Runtime.getRuntime().exec(arrayOf("chmod", "755", local.absolutePath)).waitFor() } catch (e: Exception) {}
        return local
    }

    private suspend fun downloadBinary(
        context: Context,
        repository: V2RayRepository,
        url: String,
        zipEntry: String,
        destination: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            repository.log("SYSTEM", "INFO", "Downloading $url ...")
            val tmp = File(context.cacheDir, "tmp_download_${destination.name}")
            if (tmp.exists()) tmp.delete()

            java.net.URL(url).openStream().use { it.copyTo(tmp.outputStream()) }
            repository.log("SYSTEM", "SUCCESS", "Downloaded ${tmp.length()} bytes.")

            val isZip = try { java.util.zip.ZipInputStream(tmp.inputStream()).use { it.nextEntry != null } } catch (e: Exception) { false }
            if (isZip) {
                var extracted = false
                java.util.zip.ZipInputStream(tmp.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == zipEntry || entry.name.endsWith("/$zipEntry")) {
                            destination.outputStream().use { zip.copyTo(it) }
                            extracted = true; break
                        }
                        entry = zip.nextEntry
                    }
                }
                tmp.delete()
                if (!extracted) { repository.log("SYSTEM", "ERROR", "$zipEntry not found in ZIP."); return@withContext false }
            } else {
                tmp.renameTo(destination)
            }

            destination.setReadable(true, false)
            destination.setExecutable(true, false)
            destination.setExecutable(true, true)
            try { Runtime.getRuntime().exec(arrayOf("chmod", "755", destination.absolutePath)).waitFor() } catch (e: Exception) {}
            repository.log("SYSTEM", "SUCCESS", "${destination.name} ready.")
            return@withContext true
        } catch (e: Exception) {
            repository.log("SYSTEM", "ERROR", "Download failed: ${e.localizedMessage}")
            return@withContext false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "V2Ray Dan", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try { tun2socksProcess?.destroy(); tun2socksProcess = null } catch (e: Exception) {}
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }
}
