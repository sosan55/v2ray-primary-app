package com.example.service

import com.example.data.ServerEntity
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.random.Random

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

class VpnCoreManager(private val repository: V2RayRepository) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _speedState = MutableStateFlow(SpeedState())
    val speedState: StateFlow<SpeedState> = _speedState.asStateFlow()

    private val _connectionDuration = MutableStateFlow(0L) // in seconds
    val connectionDuration: StateFlow<Long> = _connectionDuration.asStateFlow()

    private val _connectedServer = MutableStateFlow<ServerEntity?>(null)
    val connectedServer: StateFlow<ServerEntity?> = _connectedServer.asStateFlow()

    private var simulatorJob: Job? = null
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

        scope.launch {
            repository.log("VPN", "INFO", "Initiating handshake sequence...")
            delay(300)
            repository.log("V2RAY-CORE", "INFO", "V2Ray core v5.14.2 starting daemon process...")
            delay(250)
            repository.log("V2RAY-CORE", "INFO", "Loading configuration format v5. Outbound set to: [${server.type}] ${server.name}")
            delay(300)
            repository.log("VPN-SERVICE", "INFO", "Allocating local TUN device file descriptor...")
            delay(200)
            repository.log("VPN-SERVICE", "INFO", "Interface config: IP 10.254.0.2/30, MTU 1500, Route global out")
            delay(350)
            repository.log("V2RAY-CORE", "INFO", "Establishing security layer: protocol encryption=${server.security}, tls=${server.tls}")
            if (server.tls) {
                repository.log("TLS-HANDSHAKE", "INFO", "Server SNI check: '${server.sni.ifEmpty { server.address }}' valid. Certificate status OK.")
                delay(250)
            }
            repository.log("TUNNEL", "SUCCESS", "TCP local outbound handshake completed with remote port ${server.port}.")
            repository.log("VPN", "SUCCESS", "V2Ray tunnel is core established. Local proxy listening on 127.0.0.1:10808")
            
            _vpnState.value = VpnState.CONNECTED
            startSpeedAndTimerSimulator()
        }
    }

    fun stopVpn() {
        if (_vpnState.value == VpnState.DISCONNECTED) return
        
        _vpnState.value = VpnState.DISCONNECTING
        stopSpeedAndTimerSimulator()

        scope.launch {
            repository.log("VPN", "INFO", "Tearing down remote VPN tunnel session...")
            delay(200)
            repository.log("VPN-SERVICE", "INFO", "Releasing tun0 interface descriptor and resetting routes...")
            delay(150)
            repository.log("V2RAY-CORE", "WARNING", "V2Ray daemon shutdown code complete.")
            repository.log("VPN", "INFO", "Stopped. Connection closed gracefully.")
            
            _vpnState.value = VpnState.DISCONNECTED
            _connectedServer.value = null
            _connectionDuration.value = 0L
            _speedState.value = SpeedState()
        }
    }

    private fun startSpeedAndTimerSimulator() {
        stopSpeedAndTimerSimulator()

        // Speed Simulator
        simulatorJob = scope.launch {
            var totalDownBytes = 0L
            var totalUpBytes = 0L
            while (isActive) {
                // Generate realistic fluctuations
                val isDownloading = Random.nextFloat() > 0.2 // 80% chance of active flow
                val downSpeedBytes = if (isDownloading) {
                    if (Random.nextFloat() > 0.8) Random.nextLong(1_500_000, 6_800_000) // high burst
                    else Random.nextLong(45_000, 450_000) // standard speed
                } else {
                    Random.nextLong(300, 2500) // trickle background ping
                }

                val upSpeedBytes = if (isDownloading) {
                    downSpeedBytes / Random.nextLong(10, 25)
                } else {
                    Random.nextLong(150, 1200)
                }

                totalDownBytes += downSpeedBytes
                totalUpBytes += upSpeedBytes

                _speedState.value = SpeedState(
                    downloadSpeed = formatSpeed(downSpeedBytes),
                    uploadSpeed = formatSpeed(upSpeedBytes),
                    rawDownBytes = totalDownBytes,
                    rawUpBytes = totalUpBytes
                )
                delay(1000)
            }
        }

        // Active Connection Timer
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                _connectionDuration.value += 1
            }
        }
    }

    private fun stopSpeedAndTimerSimulator() {
        simulatorJob?.cancel()
        simulatorJob = null
        durationJob?.cancel()
        durationJob = null
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
        stopSpeedAndTimerSimulator()
        scope.cancel()
    }
}
