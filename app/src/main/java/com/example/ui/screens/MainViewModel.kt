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

    // Flow for VPN integration requests from MainActivity
    private val _vpnPermissionRequest = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequest = _vpnPermissionRequest.asSharedFlow()

    // Data flows from DB
    val servers: StateFlow<List<ServerEntity>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeServer: StateFlow<ServerEntity?> = repository.activeServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = repository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI flows from simulated Core Service
    val vpnState: StateFlow<VpnState> = vpnCoreManager.vpnState
    val speedState: StateFlow<SpeedState> = vpnCoreManager.speedState
    val connectionDuration: StateFlow<Long> = vpnCoreManager.connectionDuration
    val connectedServer: StateFlow<ServerEntity?> = vpnCoreManager.connectedServer

    // Activity indicator states
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isTestingPing = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isTestingPing: StateFlow<Map<Long, Boolean>> = _isTestingPing.asStateFlow()

    // Background optimizer service active status and launcher toggle (Disabled from root)
    val isAutoConnectActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun toggleAutoConnect() {
        // Disabled from root as requested
    }

    // Preferences configuration (Saved locally inside DB or simple local memory)
    private val _routingMode = MutableStateFlow("Bypass LAN & Mainland") // Bypass LAN & Mainland, Global, Direct
    val routingMode: StateFlow<String> = _routingMode.asStateFlow()

    private val _dnsServer = MutableStateFlow("1.1.1.1")
    val dnsServer: StateFlow<String> = _dnsServer.asStateFlow()

    private val _bypassList = MutableStateFlow(true)
    val bypassList: StateFlow<Boolean> = _bypassList.asStateFlow()

    init {
        // Seed default servers if the DB is empty on first launch or clean up obsolete demo placeholders
        viewModelScope.launch(Dispatchers.IO) {
            val currentServers = repository.allServers.first()
            val obsoleteServers = currentServers.filter { it.address.contains("v2raydan.xyz") || it.address.contains("v2raydan") }
            if (obsoleteServers.isNotEmpty()) {
                repository.log("SYSTEM", "WARNING", "Cleaning up obsolete demo placeholders and migrating database...")
                obsoleteServers.forEach { repository.deleteServer(it) }
            }
            val updatedServers = repository.allServers.first()
            if (updatedServers.isEmpty()) {
                repository.log("SYSTEM", "INFO", "Instantiating high-performance active gate servers...")
                val defaultGateways = listOf(
                    ServerEntity(
                        name = "🇺🇸 United States - Cloudflare Anycast",
                        type = "VLESS",
                        address = "cloudflare.com",
                        port = 443,
                        uuid = "21e28fa0-0d2d-419f-b9d9-930ecf9cf719",
                        tls = true,
                        sni = "cloudflare.com"
                    ),
                    ServerEntity(
                        name = "🇬🇧 London - Cloud DNS Gateway",
                        type = "VMESS",
                        address = "one.one.one.one",
                        port = 443,
                        uuid = "3196edbb-0e2e-43fa-a6f6-43d9203eff3a",
                        network = "ws",
                        path = "/dns-query",
                        tls = true
                    ),
                    ServerEntity(
                        name = "🇯🇵 Tokyo - Low-Latency Anycast",
                        type = "TROJAN",
                        address = "dns.google",
                        port = 443,
                        uuid = "jp-psw-danvpn",
                        tls = true,
                        sni = "dns.google"
                    ),
                    ServerEntity(
                        name = "🇩🇪 Germany - Frankfurt Speed Hub",
                        type = "VLESS",
                        address = "speed.cloudflare.com",
                        port = 443,
                        uuid = "7a6e12e1-419b-4ff2-a4e1-22e3ad5b78ff",
                        tls = true,
                        sni = "speed.cloudflare.com",
                        network = "ws",
                        path = "/vless"
                    ),
                    ServerEntity(
                        name = "🇫🇷 France - Paris Edge Node",
                        type = "SHADOWSOCKS",
                        address = "dns.quad9.net",
                        port = 443,
                        uuid = "chacha20-ietf-poly1305:mypassword1",
                        tls = true,
                        network = "tcp"
                    )
                )
                repository.insertServers(defaultGateways)
                // Select the first one as active by default
                val firstId = repository.allServers.first().firstOrNull()?.id
                if (firstId != null) {
                    repository.selectServer(firstId)
                }
            }
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
            // If connected, we should stop and reconnect to the new server
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
        sni: String,
        security: String = "none",
        flow: String = "",
        fingerprint: String = "",
        publicKey: String = "",
        shortId: String = ""
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
                sni = sni,
                security = security,
                flow = flow,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId
            )
            repository.addServer(newServer)
        }
    }

    fun updateManualServer(
        id: Long,
        name: String,
        type: String,
        address: String,
        port: Int,
        uuid: String,
        network: String,
        path: String,
        tls: Boolean,
        sni: String,
        security: String = "none",
        flow: String = "",
        fingerprint: String = "",
        publicKey: String = "",
        shortId: String = ""
    ) {
        viewModelScope.launch {
            val updated = ServerEntity(
                id = id,
                name = name.ifEmpty { "$type Server" },
                type = type.uppercase(),
                address = address.ifEmpty { "127.0.0.1" },
                port = port,
                uuid = uuid,
                network = network,
                path = path,
                tls = tls,
                sni = sni,
                security = security,
                flow = flow,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId
            )
            repository.updateServer(updated)
            repository.log("USER", "INFO", "Updated server profile: ${updated.name} (ID: $id)")
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            // If selected server was deleted, elect a new one
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
            val list = servers.value
            list.forEach { s ->
                triggerPing(s.id)
            }
        }
    }

    fun setRoutingMode(mode: String) {
        _routingMode.value = mode
        viewModelScope.launch {
            repository.log("ROUTING", "INFO", "Changed proxy routing strategy to: $mode")
        }
    }

    fun setDnsServer(dns: String) {
        _dnsServer.value = dns
        viewModelScope.launch {
            repository.log("SYSTEM", "INFO", "Updated DNS server address: $dns")
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
            viewModelScope.launch {
                repository.log("SUBSCRIPTION", "WARNING", "Removed subscription file: ${sub.name}")
            }
        }
    }

    fun syncAllSubscriptions() {
        viewModelScope.launch {
            _isSyncing.value = true
            val subs = subscriptions.value
            if (subs.isEmpty()) {
                repository.log("SUBSCRIPTION", "WARNING", "No custom subscriptions configured. Creating standard demo feeds...")
                val demoSubId = repository.addSubscription("🚀 UltraFast Anycast Network", "https://cloudflare.com/sub/feed?key=demo")
                val sampleSub = SubscriptionEntity(id = demoSubId, name = "🚀 UltraFast Anycast Network", url = "https://cloudflare.com/sub/feed?key=demo")
                repository.syncSubscription(sampleSub)
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
                repository.log("USER", "SUCCESS", "Parsed and imported server link of type ${parsed.type}!")
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
