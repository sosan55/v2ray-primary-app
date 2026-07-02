package com.example.service

import android.app.Notification
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) startVpn()
        else if (intent?.action == ACTION_STOP) stopVpn()
        return START_STICKY
    }

    private fun startVpn() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال..."))

        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                stopSelf(); return@launch
            }

            try {
                // 1. کپی فایل‌های دیتابیس (بسیار مهم)
                copyAssetFileIfNeeded("geoip.dat")
                copyAssetFileIfNeeded("geosite.dat")

                // 2. راه‌اندازی TUN
                val builder = Builder().setSession("V2RayDan").addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").setMtu(1500)
                
                interfaceDescriptor = builder.establish()
                val fd = interfaceDescriptor?.fd ?: return@launch

                // 3. تنظیم محیط هسته
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                val configJson = XrayConfigGenerator.generate(server, fd)
                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
                    override fun shutdown(): Long = 0L
                    override fun startup(): Long = 0L
                })
                
                coreController = controller
                controller.startLoop(configJson, fd)
                
                updateNotification("متصل شد")
            } catch (e: Exception) {
                Log.e("VPN_ERROR", e.message ?: "Unknown error")
                stopSelf()
            }
        }
    }

    private fun copyAssetFileIfNeeded(fileName: String) {
        val outFile = File(filesDir, fileName)
        if (!outFile.exists() || outFile.length() == 0L) {
            try {
                assets.open(fileName).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) { Log.e("ASSETS", "Error: ${e.message}") }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun stopVpn() {
        coreController?.stopLoop()
        interfaceDescriptor?.close()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
