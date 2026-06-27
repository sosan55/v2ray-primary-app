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
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File

class V2RayVpnService : VpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null
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

            // ── 2. xray config ────────────────────────────────────────────
            val configJson = buildConfig(fd, server)
            val configFile = File(cacheDir, "xray_config.json")
            try {
                configFile.writeText(configJson)
                repository.log("CONFIG", "SUCCESS", "Config written.")
            } catch (e: Exception) {
                repository.log("CONFIG", "ERROR", "Failed to write config: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf(); return@launch
            }

            // ── 3. xray via JNI ───────────────────────────────────────────
            try {
                val vpnSupport = object : V2RayVPNServiceSupportsSet {
                    override fun shutdown(): Long { stopVpn(); return 0 }
                    override fun prepare(): Long = 0
                    override fun protect(l: Long): Boolean = protect(l.toInt())
                    override fun onEmitStatus(l: Long, s: String?): Long {
                        Log.d("XRAY-JNI", "Status[$l]: $s")
                        return 0
                    }
                    override fun setup(s: String?): Long = 0
                }

                val point = Libv2ray.newV2RayPoint(vpnSupport, false)
                v2rayPoint = point
                point.configureFileLocationAsset = filesDir.absolutePath
                point.domainName = server.address
                point.configureV2Ray(configJson)

                repository.log("XRAY-CORE", "INFO", "Starting xray via JNI...")
                val result = point.runLoop(false)

                if (result != 0L) {
                    repository.log("XRAY-CORE", "ERROR", "xray JNI failed. Code: $result")
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }
                    stopSelf(); return@launch
                }

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

    private fun buildConfig(fd: Int, server: com.example.data.ServerEntity): String {
        val outbound = XrayConfigGenerator.generate(server, -1)
        // outbounds رو از config generator استخراج میکنیم
        val outbounds = extractOutbounds(outbound)
        return """
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "port": 10808,
      "listen": "127.0.0.1",
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
    },
    {
      "tag": "tun-in",
      "protocol": "dokodemo-door",
      "port": 10801,
      "listen": "127.0.0.1",
      "settings": { "network": "tcp,udp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "tproxy" } }
    }
  ],
  "outbounds": $outbounds,
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "direct" }
    ]
  }
}
        """.trimIndent()
    }

    private fun extractOutbounds(config: String): String {
        return try {
            val start = config.indexOf("\"outbounds\"")
            val arrStart = config.indexOf('[', start)
            var depth = 0
            var end = arrStart
            for (i in arrStart until config.length) {
                when (config[i]) {
                    '[', '{' -> depth++
                    ']', '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            config.substring(arrStart, end + 1)
        } catch (e: Exception) {
            """[{"protocol":"freedom","settings":{},"tag":"proxy"},{"protocol":"freedom","settings":{},"tag":"direct"}]"""
        }
    }

    private fun stopVpn() {
        try { v2rayPoint?.stopLoop(); v2rayPoint = null } catch (e: Exception) { Log.e("VPN", "xray stop: ${e.localizedMessage}") }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "V2Ray Dan", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try { v2rayPoint?.stopLoop(); v2rayPoint = null } catch (e: Exception) {}
        try { interfaceDescriptor?.close(); interfaceDescriptor = null } catch (e: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }
}
