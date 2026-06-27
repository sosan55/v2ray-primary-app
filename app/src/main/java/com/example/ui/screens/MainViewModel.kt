package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LogEntity
import com.example.data.ServerEntity
import com.example.data.SubscriptionEntity
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import com.example.service.SpeedState
import com.example.service.VpnCoreManager
import com.example.service.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = V2RayDatabase.getDatabase(application)
    val repository = V2RayRepository(database)
    val vpnCoreManager = VpnCoreManager(application, repository)

    private val _vpnPermissionRequest = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequest = _vpnPermissionRequest.asSharedFlow()

    val servers: StateFlow<List<ServerEntity>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeServer: StateFlow<ServerEntity?> = repository.activeServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = repository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vpnState: StateFlow<VpnState> = vpnCoreManager.vpnState
    val speedState: StateFlow<SpeedState> = vpnCoreManager.speedState
    val connectionDuration: StateFlow<Long> = vpnCoreManager.connectionDuration
    val connectedServer: StateFlow<ServerEntity?> = vpnCoreManager.connectedServer

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isTestingPing = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isTestingPing: StateFlow<Map<Long, Boolean>> = _isTestingPing.asStateFlow()

    val isAutoConnectActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun toggleAutoConnect() {
        // Disabled
    }

    private val _routingMode = MutableStateFlow("Bypass LAN & Mainland")
    val routingMode: StateFlow<String> = _routingMode.asStateFlow()

    private val _dnsServer = MutableStateFlow("1.1.1.1")
    val dnsServer: StateFlow<String> = _dnsServer.asStateFlow()

    private val _bypassList = MutableStateFlow(true)
    val bypassList: StateFlow<Boolean> = _bypassList.asStateFlow()

    init {
        // هیچ سرور پیش‌فرضی اضافه نمیشه — کاربر باید خودش subscription یا سرور اضافه کنه
        viewModelScope.launch(Dispatchers.IO) {
            repository.log("SYSTEM", "INFO", "App started. Waiting for user to add servers or subscriptions.")
        }
    }

    fun toggleVpn() {
        viewModelScope.launch {
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
                vpnCoreManager.stopVpn()
            } else {
                val currentActive = activeServer.value
                val context = getApplication<Application>()
                val vpnIntent = try {
                    android.net.VpnService.prepare(context)
                } catch (e: Exception) {
                    repository.log("VPN", "WARNING", "VpnService.prepare bypassed: ${e.localizedMessage}")
                    null
                }
                if (vpnIntent != null) {
                    _vpnPermissionRequest.tryEmit(vpnIntent)
                } else {
                    vpnCoreManager.toggleVpn(currentActive)
                }
            }
        }
    }

    fun toggleVpnAfterPermission() {
        viewModelScope.launch {
            val currentActive = activeServer.value
            vpnCoreManager.toggleVpn(currentActive)
        }
    }

    fun selectServer(server: ServerEntity) {
        viewModelScope.launch {
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
                vpnCoreManager.stopVpn()
                repository.selectServer(server.id)
                vpnCoreManager.toggleVpn(server)
            } else {
                repository.selectServer(server.id)
            }
        }
    }

    fun addManualServer(
        name: String,
        type: String,
        address: String,
        port: Int,
        uuid: String,
        network: String,
        path: String,
        tls: Boolean,
        sni: String
    ) {
        viewModelScope.launch {
            val newServer = ServerEntity(
                name = name.ifEmpty { "$type Server" },
                type = type.uppercase(),
                address = address.ifEmpty { "127.0.0.1" },
                port = port,
                uuid = uuid,
                network = network,
                path = path,
                tls = tls,
                sni = sni
            )
            repository.addServer(newServer)
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            val active = activeServer.value
            if (active?.id == server.id) {
                val remaining = servers.value.filter { it.id != server.id }
                if (remaining.isNotEmpty()) {
                    repository.selectServer(remaining.first().id)
                }
            }
        }
    }

    fun triggerPing(serverId: Long) {
        viewModelScope.launch {
            _isTestingPing.update { it + (serverId to true) }
            repository.testServerPing(serverId)
            _isTestingPing.update { it + (serverId to false) }
        }
    }

    fun pingAllServers() {
        viewModelScope.launch {
            servers.value.forEach { s -> triggerPing(s.id) }
        }
    }

    fun setRoutingMode(mode: String) {
        _routingMode.value = mode
        viewModelScope.launch {
            repository.log("ROUTING", "INFO", "Changed routing mode to: $mode")
        }
    }

    fun setDnsServer(dns: String) {
        _dnsServer.value = dns
        viewModelScope.launch {
            repository.log("SYSTEM", "INFO", "Updated DNS: $dns")
        }
    }

    fun addSubscriptionUrl(name: String, url: String) {
        viewModelScope.launch {
            repository.addSubscription(name, url)
        }
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            repository.deleteSubscription(sub)
            repository.log("SUBSCRIPTION", "WARNING", "Removed subscription: ${sub.name}")
        }
    }

    fun syncAllSubscriptions() {
        viewModelScope.launch {
            _isSyncing.value = true
            val subs = subscriptions.value
            if (subs.isEmpty()) {
                // هیچ subscription‌ای نیست — به کاربر اطلاع بده
                repository.log("SUBSCRIPTION", "WARNING", "No subscriptions configured. Please add a subscription URL first.")
            } else {
                subs.forEach { sub ->
                    repository.syncSubscription(sub)
                }
            }
            _isSyncing.value = false
        }
    }

    fun importFromClipboard(rawLink: String): Boolean {
        val parsed = repository.parseShareLink(rawLink.trim())
        return if (parsed != null) {
            viewModelScope.launch {
                repository.addServer(parsed)
                repository.log("USER", "SUCCESS", "Imported server: ${parsed.type} - ${parsed.name}")
            }
            true
        } else {
            false
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    override fun onCleared() {
        super.onCleared()
        vpnCoreManager.cleanUp()
    }
}
