package app.truenascompanion.data.wol

import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 1.12.0: where a wake-up stands, per server. */
sealed interface WakeState {
    data object Idle : WakeState
    data class Waking(val startedAt: Long) : WakeState
    data object Online : WakeState
    data object TimedOut : WakeState
    data class Failed(val message: String) : WakeState
}

/** Reachability dot on the server cards: unknown until probed. */
enum class Reach { UNKNOWN, CHECKING, ONLINE, OFFLINE }

/**
 * Sends the magic packet and then polls the saved addresses (a plain TCP connect, every 5 s, up to ~2.5 min) until the
 * NAS answers. One wake per server at a time. Used by the server cards, the connection-lost card, the Quick Settings
 * tile, automation and the watch; none of them need a confirmation, because waking a NAS changes nothing on it.
 */
class WakeController(
    private val scope: CoroutineScope,
    private val sender: suspend (WolConfig) -> Int = { WakeOnLan.send(it) },
    private val probe: suspend (ServerConfig) -> Boolean = { WakeOnLan.reachable(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onOnline: (ServerConfig) -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<String, WakeState>>(emptyMap())
    val states: StateFlow<Map<String, WakeState>> = _states.asStateFlow()
    private val _reach = MutableStateFlow<Map<String, Reach>>(emptyMap())
    val reach: StateFlow<Map<String, Reach>> = _reach.asStateFlow()
    private val jobs = HashMap<String, Job>()

    fun state(id: String): WakeState = _states.value[id] ?: WakeState.Idle

    /** Starts a wake-up; false when the server has no WoL settings or a wake is already running. */
    @Synchronized
    fun wake(server: ServerConfig): Boolean {
        val cfg = server.wol ?: return false
        if (jobs[server.id]?.isActive == true) return false
        set(server.id, WakeState.Waking(clock()))
        jobs[server.id] = scope.launch {
            try {
                sender(cfg)
            } catch (e: Exception) {
                set(server.id, WakeState.Failed(e.message ?: "Couldn't send the wake-up packet."))
                return@launch
            }
            val start = clock()
            while (isActive) {
                delay(WakeOnLan.POLL_INTERVAL_MS)
                if (probe(server)) {
                    set(server.id, WakeState.Online)
                    _reach.update { it + (server.id to Reach.ONLINE) }
                    onOnline(server)
                    return@launch
                }
                if (clock() - start >= WakeOnLan.WAKE_TIMEOUT_MS) {
                    set(server.id, WakeState.TimedOut)
                    return@launch
                }
            }
        }
        return true
    }

    fun clear(id: String) = set(id, WakeState.Idle)

    /** Probes each server once (the All servers dots). */
    fun probeAll(servers: List<ServerConfig>) {
        servers.forEach { s ->
            if (_reach.value[s.id] == Reach.CHECKING) return@forEach
            _reach.update { it + (s.id to Reach.CHECKING) }
            scope.launch { _reach.update { it + (s.id to if (probe(s)) Reach.ONLINE else Reach.OFFLINE) } }
        }
    }

    private fun set(id: String, s: WakeState) = _states.update { it + (id to s) }

    companion object {
        /** Text for the server card / failure card. */
        fun label(s: WakeState, now: Long = System.currentTimeMillis()): String? = when (s) {
            WakeState.Idle -> null
            is WakeState.Waking -> "Waking… (up to ~2 min)" + ((now - s.startedAt) / 1000).let { if (it >= 5) " · ${it}s" else "" }
            WakeState.Online -> "Online"
            WakeState.TimedOut -> "No answer yet. Check that Wake-on-LAN is on in the NAS's BIOS and that the phone is on the same network."
            is WakeState.Failed -> s.message
        }
    }
}
