package app.truenascompanion.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.NetworkApi
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.wol.Reach
import app.truenascompanion.data.wol.WakeController
import app.truenascompanion.data.wol.WakeOnLan
import app.truenascompanion.data.wol.WakeState
import app.truenascompanion.data.wol.WolConfig
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.Scaffold
import app.truenascompanion.ui.components.TopAppBar
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 1.12.0: the Wake-on-LAN form. */
data class WakeForm(
    val loaded: Boolean = false,
    val serverName: String = "",
    val mac: String = "",
    val interfaceName: String? = null,
    val broadcast: String = "",
    val port: Int = WakeOnLan.DEFAULT_PORT,
    val relay: String = "",
    val candidates: List<WakeOnLan.Candidate> = emptyList(),
    /** null = not tried; "" = loaded; else why the interfaces couldn't be read. */
    val interfacesNote: String? = null,
    val loadingInterfaces: Boolean = false,
    val hadConfig: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    fun config() = WolConfig(
        mac = WakeOnLan.normalizeMac(mac) ?: mac.trim(),
        interfaceName = interfaceName,
        broadcast = broadcast.trim().ifBlank { null },
        port = port,
        relay = relay.trim().ifBlank { null },
    )
}

class WakeSetupViewModel(private val c: AppContainer, private val serverId: String) : ViewModel() {
    private val _form = MutableStateFlow(WakeForm())
    val form: StateFlow<WakeForm> = _form

    init {
        viewModelScope.launch {
            val s = c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val w = s.wol
            _form.value = WakeForm(
                loaded = true, serverName = s.name, hadConfig = w != null,
                mac = w?.mac.orEmpty(), interfaceName = w?.interfaceName, broadcast = w?.broadcast.orEmpty(),
                port = w?.port ?: WakeOnLan.DEFAULT_PORT, relay = w?.relay.orEmpty(),
            )
            loadInterfaces(s, autoFill = w == null)
        }
    }

    /** Reads `interface.query` while this server is connected (read-only, NETWORK_INTERFACE_READ). */
    fun loadInterfaces(server: ServerConfig? = null, autoFill: Boolean = false) = viewModelScope.launch {
        val s = server ?: c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
        if (c.repository.activeServer.value?.id != serverId) {
            _form.update { it.copy(interfacesNote = "Connect to ${s.name} first to fill this in from the NAS, or type the MAC address yourself.") }
            return@launch
        }
        _form.update { it.copy(loadingInterfaces = true) }
        val result = runCatching { c.repository.call { NetworkApi(it).interfaces() } }
        _form.update { f ->
            result.fold(
                onSuccess = { list ->
                    val cands = WakeOnLan.candidates(list)
                    val best = WakeOnLan.bestCandidate(cands, WakeOnLan.hosts(s))
                    val fill = autoFill && f.mac.isBlank() && best != null
                    f.copy(
                        loadingInterfaces = false, candidates = cands,
                        interfacesNote = if (cands.isEmpty()) "The NAS didn't list a network card with a MAC address." else "",
                        mac = if (fill) best!!.mac else f.mac,
                        interfaceName = if (fill) best!!.interfaceName else f.interfaceName,
                        broadcast = if (fill) best!!.broadcast.orEmpty() else f.broadcast,
                    )
                },
                onFailure = { f.copy(loadingInterfaces = false, interfacesNote = "Couldn't read the network cards (${it.message ?: "error"}). Type the MAC address yourself.") },
            )
        }
    }

    fun pick(cand: WakeOnLan.Candidate) = _form.update { it.copy(mac = cand.mac, interfaceName = cand.interfaceName, broadcast = cand.broadcast.orEmpty(), error = null) }
    fun edit(f: (WakeForm) -> WakeForm) = _form.update { f(it).copy(error = null) }

    fun save() {
        val cfg = _form.value.config()
        WakeOnLan.problem(cfg)?.let { p -> _form.update { it.copy(error = p) }; return }
        viewModelScope.launch {
            c.settings.updateServer(serverId) { it.copy(wol = cfg) }
            _form.update { it.copy(saved = true) }
        }
    }

    fun remove() = viewModelScope.launch {
        c.settings.updateServer(serverId) { it.copy(wol = null) }
        _form.update { it.copy(saved = true) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WakeSetupScreen(serverId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "wake-$serverId") { WakeSetupViewModel(it, serverId) }
    val f by vm.form.collectAsStateWithLifecycle()
    LaunchedEffect(f.saved) { if (f.saved) onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Wake-on-LAN") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { padding ->
        WakeSetupContent(f, Modifier.padding(padding), onPick = vm::pick, onEdit = vm::edit, onReload = { vm.loadInterfaces() }, onSave = vm::save, onRemove = vm::remove)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WakeSetupContent(
    f: WakeForm,
    modifier: Modifier = Modifier,
    onPick: (WakeOnLan.Candidate) -> Unit = {},
    onEdit: ((WakeForm) -> WakeForm) -> Unit = {},
    onReload: () -> Unit = {},
    onSave: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    var advanced by rememberSaveable { mutableStateOf(f.relay.isNotBlank()) }
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Turn ${f.serverName.ifBlank { "your NAS" }} on from the phone. The phone sends a \u201cmagic packet\u201d on your network, and a NAS that's switched off but plugged in wakes up.",
            style = MaterialTheme.typography.bodyMedium,
        )
        WakeHint(
            "Works on your home Wi-Fi. Wake-up packets don't cross the internet, so away from home it only works through the " +
                "built-in WireGuard or Tailscale if your router passes the broadcast on, or through a relay (below).",
        )
        WakeHint("Wake-on-LAN must be switched on in the NAS's BIOS/UEFI (often \u201cWake on LAN\u201d or \u201cPower on by PCI-E\u201d) and on its network card.")

        Text("Network card", style = MaterialTheme.typography.titleSmall)
        when {
            f.loadingInterfaces -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Reading the NAS's network cards…")
            }
            f.candidates.isNotEmpty() -> Column {
                f.candidates.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(c) }.testTag("wol_if_${c.interfaceName}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = f.interfaceName == c.interfaceName && f.mac.equals(c.mac, true), onClick = { onPick(c) })
                        Column(Modifier.weight(1f)) {
                            Text(c.interfaceName, style = MaterialTheme.typography.bodyLarge)
                            Text(listOfNotNull(c.mac, c.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        f.interfacesNote?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onReload) { Text("Try again") }
        }

        OutlinedTextField(
            value = f.mac, onValueChange = { v -> onEdit { it.copy(mac = v, interfaceName = null) } },
            label = { Text("MAC address") }, placeholder = { Text("3c:ec:ef:12:34:56") }, singleLine = true,
            isError = f.mac.isNotBlank() && WakeOnLan.parseMac(f.mac) == null,
            modifier = Modifier.fillMaxWidth().testTag("wol_mac"),
        )
        OutlinedTextField(
            value = f.broadcast, onValueChange = { v -> onEdit { it.copy(broadcast = v) } },
            label = { Text("Broadcast address") }, placeholder = { Text("192.168.1.255") }, singleLine = true,
            supportingText = { Text("The subnet's broadcast. 255.255.255.255 is always sent too.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Port", style = MaterialTheme.typography.titleSmall)
        val ports = listOf(WakeOnLan.DEFAULT_PORT, WakeOnLan.ALT_PORT)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ports.forEachIndexed { i, p ->
                SegmentedButton(selected = f.port == p, onClick = { onEdit { it.copy(port = p) } }, shape = SegmentedButtonDefaults.itemShape(i, ports.size)) {
                    Text(if (p == 9) "9 (usual)" else "7 (echo)")
                }
            }
        }

        TextButton(onClick = { advanced = !advanced }) {
            Text("Advanced: relay or directed broadcast")
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(advanced) {
            OutlinedTextField(
                value = f.relay, onValueChange = { v -> onEdit { it.copy(relay = v) } },
                label = { Text("Relay (host:port)") }, placeholder = { Text("router.home.arpa:9") }, singleLine = true,
                supportingText = { Text("A router or Raspberry Pi that forwards wake-up packets, or a directed broadcast your router lets through. Leave empty if you don't have one.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        f.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        GlowButton(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save") }
        if (f.hadConfig) TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Turn off Wake-on-LAN for this server") }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun WakeHint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Entry card in the server editor (like the VPN card). */
@Composable
fun WakeEntryCard(configured: Boolean, onClick: () -> Unit) {
    ElevatedSection(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Wake-on-LAN", style = MaterialTheme.typography.titleSmall)
                Text(if (configured) "Set up: tap Wake on the server card or the Quick Settings tile." else "Turn the NAS on from your phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Reachability dot on a server card (green online, red offline, grey unknown). */
@Composable
fun ReachDot(reach: Reach, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (color, label) = when (reach) {
        Reach.ONLINE -> status.healthy to "Reachable"
        Reach.OFFLINE -> status.critical to "Not reachable"
        Reach.CHECKING -> MaterialTheme.colorScheme.outline to "Checking"
        Reach.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant to "Unknown"
    }
    Box(modifier.size(10.dp).background(color, CircleShape).semantics { contentDescription = label })
}

/** "Wake" button plus the live "Waking… / Online" line. */
@Composable
fun WakeAction(state: WakeState, onWake: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state is WakeState.Waking) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(modifier) {
        FilledTonalButton(
            onClick = onWake, enabled = state !is WakeState.Waking,
            modifier = (if (compact) Modifier else Modifier.fillMaxWidth()).heightIn(min = 48.dp).testTag("wake_button"),
        ) {
            if (state is WakeState.Waking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state is WakeState.Waking) "Waking…" else "Wake")
        }
        WakeController.label(state, now)?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = if (state == WakeState.Online) LocalStatusColors.current.healthy else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
