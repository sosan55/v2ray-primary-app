package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class V2RayVpnService : VpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP  = "com.example.service.STOP"
        private const val CHANNEL_ID      = "v2ray_vpn_service_channel"
        private const val NOTIFICATION_ID = 1002
        private const val TUN_ADDR        = "10.0.0.2"
        private const val TUN_PREFIX      = 24
        private const val TUN_MTU         = 1500
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
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                repository.log("VPN", "ERROR", "No server selected.")
                stopSelf(); return@launch
            }

            // ── 1. TUN interface ──────────────────────────────────────────
            val fd: Int
            try {
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress(TUN_ADDR, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(TUN_MTU)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor == null) return@launch
                fd = interfaceDescriptor!!.fd
            } catch (e: Exception) {
                stopSelf(); return@launch
            }

            // ── 2. Xray Initialization ──────────────────────────────────
            try {
                // کپی فایل‌های دیتابیس به مسیر فایل‌های اپلیکیشن
                copyAssetFileIfNeeded("geoip.dat")
                copyAssetFileIfNeeded("geosite.dat")

                // ست کردن مسیر محیطی هسته برای پیدا کردن دیتابیس‌ها
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                Log.d("XRAY_CORE", "Core initialized at: ${filesDir.absolutePath}")

                val configJson = XrayConfigGenerator.generate(server, fd)

                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
                    override fun shutdown(): Long = 0L
                    override fun startup(): Long = 0L
                })
                
                coreController = controller
                controller.startLoop(configJson, fd)
                
                updateNotification("Connected to ${server.name}")
            } catch (e: Exception) {
                Log.e("XRAY_CORE", "Error starting core: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun copyAssetFileIfNeeded(fileName: String) {
        val outFile = File(filesDir, fileName)
        // اگر فایل وجود ندارد یا حجم آن صفر است، کپی کن
        if (!outFile.exists() || outFile.length() == 0L) {
            try {
                assets.open(fileName).use { inputStream ->
                    outFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d("VPN_ASSETS", "$fileName copied. Size: ${outFile.length()}")
            } catch (e: Exception) {
                Log.e("VPN_ASSETS", "Copy error: ${e.message}")
            }
        }
    }

    private fun stopVpn() {
        coreController?.stopLoop()
        interfaceDescriptor?.close()
        stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
