package app.truenascompanion

import app.truenascompanion.data.wol.WakeController
import app.truenascompanion.wear.WearBridge
import app.truenascompanion.quick.WakeTileService
import app.truenascompanion.data.runway.RunwayStore
import app.truenascompanion.util.runCatchingCancellable

import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.net.HttpsUpgradeManager
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.vpn.TunnelManager
import app.truenascompanion.data.security.AppLock
import android.app.Application
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.quick.QuickActions
import app.truenascompanion.data.api.SessionTokenManager
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.data.security.SecretCipher
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.notify.AlertChecker
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.AlertScheduler
import app.truenascompanion.notify.BackgroundConnector
import app.truenascompanion.notify.SessionKeepAliveWorker
import app.truenascompanion.data.update.UpdateCenter
import app.truenascompanion.data.update.UpdateCheckWorker
import app.truenascompanion.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.truenascompanion.ui.servers.ServerRemovals
import app.truenascompanion.ui.servers.removeServer

/** A notification tap, app shortcut or tile asking the UI to show [destination] (with optional [arg]) for [serverId]. */
data class PendingDeepLink(val serverId: String?, val destination: String, val nonce: Long = System.nanoTime(), val arg: String? = null)

/** Tiny manual DI container — no DI framework needed for an app this size. */
class AppContainer(app: Application) {
    /** Application context (1.3.0: file browser cache, content resolver for downloads/uploads). */
    val context: android.content.Context = app
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, SecretCipher())
    val notifier = AlertNotifier(app)
    /** One signed-in socket per server, shared by the UI, the periodic check and instant alerts where possible. */
    val sharedConnections = SharedConnections()
    /** One owner of the session-token chain for the whole process (UI, workers, instant-alerts service). */
    val sessions = SessionTokenManager(settings.tokenStore)
    val backgroundConnector = BackgroundConnector(settings, sessions)
    /** Local vs remote address per network, shared by the UI, periodic checks and instant alerts. */
    val routes = RouteResolver(app)
    /** The built-in WireGuard tunnel (0.6), shared by the app, background checks and instant alerts. */
    val tunnels = TunnelManager(app) { s -> settings.wireGuard(s.id) }.also { routes.tunnels = it }
    /** 1.10.0: daily pool usage samples (storage runway). */
    val runway = RunwayStore(app)
    val alertChecker = AlertChecker(settings, backgroundConnector, notifier, sharedConnections, routes, runway)
    val repository = TrueNasRepository(settings, appScope, onSignedIn = { alertChecker.onSignedIn(it) }, shared = sharedConnections, resolver = routes, sessions = sessions)
    val deepLinks = MutableStateFlow<PendingDeepLink?>(null)
    /** 1.8.0: open (not dismissed, not snoozed) alerts of the active server, shown as a badge on the Alerts tab. */
    val alertBadge = MutableStateFlow<app.truenascompanion.ui.alerts.AlertBadge?>(null)
    val appLock = AppLock()
    /** In-app update check state (GitHub Releases of BuildConfig.UPDATE_REPO). */
    val updates = UpdateCenter()
    /** 1.7.1: moves saved http:// addresses to HTTPS (one automatic attempt per server, then the banner's button). */
    val httpsUpgrade = HttpsUpgradeManager(
        servers = { settings.servers.first() },
        update = { id, f -> settings.updateServer(id, f) },
        onChanged = { id, route ->
            routes.reset(id)
            if (route == Route.REMOTE && repository.activeServer.value?.id == id) repository.disconnect()
        },
    )
    /** 1.8.1: server removal with an Undo window (finished after it closes, or on the next start). */
    val serverRemovals = ServerRemovals(
        scope = appScope,
        mark = { settings.markPendingRemoval(it) },
        unmark = { settings.clearPendingRemoval(it) },
        pending = { settings.pendingRemovals().keys },
        finish = { removeServer(this, it) },
    )
    /** One-shot request for the Storage tab to show a segment (e.g. Protection from the dashboard card). */
    val storageTabRequest = MutableStateFlow<Int?>(null)
    /** 1.12.0: Wake-on-LAN (send + "Waking…" polling) and the reachability dots of All servers. */
    val wake = WakeController(appScope, onOnline = { s ->
        if (repository.activeServer.value?.id == s.id) repository.retryConnection()
    })
    /** 1.12.0: wakes a server by id, or the active one; false if it has no Wake-on-LAN settings. */
    suspend fun wakeServer(id: String? = null): Boolean {
        val all = settings.servers.first()
        val target = all.firstOrNull { it.id == (id ?: settings.activeServerId.first()) } ?: all.firstOrNull()
        return target?.let { wake.wake(it) } == true
    }
    /** 1.12.0: status snapshot for the Wear OS app (Data Layer; only when the watch app is installed). */
    val wear = WearBridge(app, appScope)

    /** 1.12.0: feeds the watch from the alert check and the wake controller (no polling of its own). */
    fun startWear() {
        alertChecker.afterCheck = { server, alerts, titles, data ->
            if (server.id == settings.activeServerId.first() && wear.watchConnected()) {
                val cpu = data.cpuAverage(5)?.toInt()
                val temp = data.diskTempLowest(10)?.values?.maxOrNull()?.toInt()
                wear.onChecked(server.name.ifBlank { server.displayHost }, alerts, titles, cpu, temp)
            }
        }
        appScope.launch {
            kotlinx.coroutines.flow.combine(repository.activeServer, wake.states) { s, st -> s to st }.collect { (s, st) ->
                val label = s?.let { st[it.id] }?.let { WakeController.label(it) }
                wear.onWake(label?.takeIf { it.length < 40 }, s?.wol != null)
                runCatching { android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, WakeTileService::class.java)) }
            }
        }
    }
}

class TrueNasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        // 1.7.1 (review P1-1): the widget job only exists while a widget is placed; no refresh on every process start.
        // 1.8.0 (code review): WorkManager calls run off the main thread so cold start isn't blocked by its database.
        container.appScope.launch {
            WidgetRefreshWorker.sync(this@TrueNasApp)
            UpdateCheckWorker.createChannel(this@TrueNasApp)
            // 1.8.0 (security review): NAS files opened with "Open with" don't stay in the cache across app starts.
            app.truenascompanion.data.files.FilePolicy.clearOpenWithCache(this@TrueNasApp)
        }
        container.startWear()
        container.appScope.launch {
            container.settings.autoUpdateCheck.collect { UpdateCheckWorker.sync(this@TrueNasApp, it) }
        }
        container.appScope.launch {
            container.settings.lockSettings.collect {
                container.appLock.onSettingsLoaded(it)
                container.notifier.appLockOn = it.enabled
            }
        }
        // Keep WorkManager / the instant-alerts service in sync with the settings for the life of the process.
        container.appScope.launch {
            combine(container.settings.notificationPrefs, container.settings.servers) { p, s -> p to s }
                .distinctUntilChanged()
                .collect { (prefs, servers) ->
                    AlertScheduler.sync(this@TrueNasApp, prefs, servers)
                    SessionKeepAliveWorker.sync(this@TrueNasApp, servers)
                }
        }
        // 1.2.0: after switching the web UI certificate, confirm it (system.general.checkin) once the app is connected
        // again with the newly trusted certificate; otherwise TrueNAS rolls the change back by itself.
        container.appScope.launch {
            container.repository.state.collect { st ->
                if (st !is ConnectionState.Connected) return@collect
                val server = container.repository.activeServer.value ?: return@collect
                if (!server.uiCertCheckinPending || server.certReviewRequired) return@collect
                runCatchingCancellable { container.repository.call { CertificatesApi(it).checkin() } }
                    .onSuccess { container.settings.updateServer(server.id) { it.copy(uiCertCheckinPending = false) } }
            }
        }
        container.appScope.launch {
            // allServers: a server inside its Undo window keeps its shortcuts until the removal really happens.
            container.settings.allServers.collect { list -> QuickActions.pruneShortcuts(this@TrueNasApp, list.map { it.id }.toSet()) }
        }
        // 1.8.1: a removal whose Undo window was cut short by the process dying is completed now.
        container.appScope.launch { container.serverRemovals.finishLeftovers() }
    }
}
