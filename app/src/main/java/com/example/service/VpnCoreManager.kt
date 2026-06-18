package com.example.service

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import com.example.data.ServerEntity
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class SpeedState(
    val downloadSpeed: String = "0.0 B/s",
    val uploadSpeed: String = "0.0 B/s",
    val rawDownBytes: Long = 0,
    val rawUpBytes: Long = 0
)

class VpnCoreManager(private val context: Context, private val repository: V2RayRepository) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        activeVpnCoreManager = this
    }

    companion object {
        var activeVpnCoreManager: VpnCoreManager? = null
    }
    
    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _speedState = MutableStateFlow(SpeedState())
    val speedState: StateFlow<SpeedState> = _speedState.asStateFlow()

    private val _connectionDuration = MutableStateFlow(0L) // in seconds
    val connectionDuration: StateFlow<Long> = _connectionDuration.asStateFlow()

    private val _connectedServer = MutableStateFlow<ServerEntity?>(null)
    val connectedServer: StateFlow<ServerEntity?> = _connectedServer.asStateFlow()

    private var trafficJob: Job? = null
    private var durationJob: Job? = null

    fun toggleVpn(server: ServerEntity?) {
        if (_vpnState.value == VpnState.DISCONNECTED || _vpnState.value == VpnState.ERROR) {
            if (server == null) {
                scope.launch {
                    repository.log("VPN", "ERROR", "Cannot start VPN: No server selected.")
                }
                _vpnState.value = VpnState.ERROR
                return
            }
            startVpn(server)
        } else {
            stopVpn()
        }
    }

    private fun startVpn(server: ServerEntity) {
        _vpnState.value = VpnState.CONNECTING
        _connectedServer.value = server
        _connectionDuration.value = 0L

        // Trigger real foreground service
        try {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = V2RayVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            scope.launch {
                repository.log("VPN-SERVICE", "ERROR", "Failed to start real VPN service: ${e.localizedMessage}")
            }
            _vpnState.value = VpnState.ERROR
        }
    }

    fun stopVpn() {
        if (_vpnState.value == VpnState.DISCONNECTED) return
        _vpnState.value = VpnState.DISCONNECTING
        stopTracking()

        // Terminate foreground service
        try {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = V2RayVpnService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            scope.launch {
                repository.log("VPN-SERVICE", "ERROR", "Failed to issue shutdown command: ${e.localizedMessage}")
            }
        }
    }

    fun updateState(state: VpnState) {
        _vpnState.value = state
    }

    fun setConnectedServer(server: ServerEntity?) {
        _connectedServer.value = server
    }

    fun startTracking() {
        stopTracking()

        // 1. Durational tracking loop
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                _connectionDuration.value += 1
            }
        }

        // 2. Real byte flow query using TrafficStats
        trafficJob = scope.launch {
            val uid = context.applicationInfo.uid
            var lastRxBytes = TrafficStats.getUidRxBytes(uid)
            var lastTxBytes = TrafficStats.getUidTxBytes(uid)
            if (lastRxBytes == TrafficStats.UNSUPPORTED.toLong()) lastRxBytes = 0
            if (lastTxBytes == TrafficStats.UNSUPPORTED.toLong()) lastTxBytes = 0

            val baseRx = lastRxBytes
            val baseTx = lastTxBytes

            while (isActive) {
                delay(1000)
                var currentRxBytes = TrafficStats.getUidRxBytes(uid)
                var currentTxBytes = TrafficStats.getUidTxBytes(uid)
                if (currentRxBytes == TrafficStats.UNSUPPORTED.toLong()) currentRxBytes = 0
                if (currentTxBytes == TrafficStats.UNSUPPORTED.toLong()) currentTxBytes = 0

                val downloadDelta = if (currentRxBytes >= lastRxBytes) currentRxBytes - lastRxBytes else 0L
                val uploadDelta = if (currentTxBytes >= lastTxBytes) currentTxBytes - lastTxBytes else 0L

                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes

                _speedState.value = SpeedState(
                    downloadSpeed = formatSpeed(downloadDelta),
                    uploadSpeed = formatSpeed(uploadDelta),
                    rawDownBytes = if (currentRxBytes >= baseRx) currentRxBytes - baseRx else 0L,
                    rawUpBytes = if (currentTxBytes >= baseTx) currentTxBytes - baseTx else 0L
                )
            }
        }
    }

    fun stopTracking() {
        trafficJob?.cancel()
        trafficJob = null
        durationJob?.cancel()
        durationJob = null
        _connectionDuration.value = 0L
        _speedState.value = SpeedState()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000 -> {
                val mbs = bytesPerSec.toDouble() / 1_000_000.0
                String.format(Locale.US, "%.1f MB/s", mbs)
            }
            bytesPerSec >= 1_000 -> {
                val kbs = bytesPerSec.toDouble() / 1_000.0
                String.format(Locale.US, "%.1f KB/s", kbs)
            }
            else -> "$bytesPerSec B/s"
        }
    }

    fun cleanUp() {
        stopTracking()
        scope.cancel()
    }
}
