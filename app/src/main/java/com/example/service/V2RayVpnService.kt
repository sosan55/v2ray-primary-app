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
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
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
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
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
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(TUN_MTU)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor == null) {
                    repository.log("TUNNEL", "ERROR", "establish() returned null.")
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }
                    stopSelf(); return@launch
                }
                fd = interfaceDescriptor!!.fd
                repository.log("TUNNEL", "SUCCESS", "TUN established. fd=$fd")
            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "TUN build failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf(); return@launch
            }

            // ── 2. config ─────────────────────────────────────────────────
            val configJson = XrayConfigGenerator.generate(server, -1)
            repository.log("CONFIG", "SUCCESS", "Config ready.")

            // ── 3. xray via JNI با fd مستقیم ─────────────────────────────
            try {
                // Init asset path برای geoip/geosite
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                val callbackHandler = object : CoreCallbackHandler {
                    override fun onEmitStatus(p0: Long, p1: String?): Long {
                        Log.d("XRAY-JNI", "Status[$p0]: $p1")
                        return 0L
                    }

                    override fun shutdown(): Long {
                        Log.d("XRAY-JNI", "Core shutdown callback")
                        return 0L
                    }

                    override fun startup(): Long {
                        Log.d("XRAY-JNI", "Core startup callback")
                        return 0L
                    }
                }

                val controller = Libv2ray.newCoreController(callbackHandler)
                coreController = controller

                repository.log("XRAY-CORE", "INFO", "Starting xray via JNI (fd=$fd)...")

                // StartLoop مستقیماً fd رو میگیره — مشکل fd sharing نداره
                controller.startLoop(configJson, fd)

                repository.log("XRAY-CORE", "SUCCESS", "xray started via JNI.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

            } catch (e: Exception) {
                repository.log("VPN", "ERROR", "JNI startup failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        try { coreController?.stopLoop(); coreController = null } catch (e: Exception) {
            Log.e("VPN", "xray stop: ${e.localizedMessage}")
        }
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) {
            Log.e("VPN", "TUN close: ${e.localizedMessage}")
        }

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "V2Ray Dan", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try { coreController?.stopLoop(); coreController = null } catch (e: Exception) {}
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }
}
