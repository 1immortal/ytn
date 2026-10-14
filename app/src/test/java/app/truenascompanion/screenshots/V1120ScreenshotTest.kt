package app.truenascompanion.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

import app.truenascompanion.ui.certs.CertsData
import app.truenascompanion.data.model.*
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.wol.Reach
import app.truenascompanion.data.wol.WakeOnLan
import app.truenascompanion.data.wol.WakeState
import app.truenascompanion.ui.AppNavDrawer
import app.truenascompanion.ui.AppNavRail
import app.truenascompanion.ui.adaptive.ListDetailFrame
import app.truenascompanion.ui.adaptive.LocalWindowLayout
import app.truenascompanion.ui.adaptive.WindowLayout
import app.truenascompanion.ui.certs.CertDetailContent
import app.truenascompanion.ui.certs.CertificatesContent
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.connection.ConnectionFailurePanel
import app.truenascompanion.ui.dashboard.*
import app.truenascompanion.ui.servers.ReachDot
import app.truenascompanion.ui.servers.WakeAction
import app.truenascompanion.ui.servers.WakeForm
import app.truenascompanion.ui.servers.WakeSetupContent
import app.truenascompanion.ui.system.HubHeader
import app.truenascompanion.ui.system.HubSummary
import app.truenascompanion.ui.system.SystemHubContent
import app.truenascompanion.ui.system.hubSubtitles
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/** 1.12.0: tablet (840 dp, 1280 dp) and landscape-phone layouts, and Wake-on-LAN. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V1120ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v1120_$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private val gib = 1024L * 1024 * 1024

    private fun shot(name: String, layout: WindowLayout, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1f), LocalWindowLayout provides layout) {
                TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(out(name))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Bar(title: String, back: Boolean = false) = TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (back) IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
    )

    // --- example data ---
    private val system = SystemInfo(
        hostname = "homenas", version = "TrueNAS-25.10.3", uptimeSeconds = 12 * 86400L, uptimeText = null,
        cpuModel = "Intel(R) Core(TM) i7-10700 CPU @ 2.90GHz", cores = 16, physicalMemory = (125.5 * gib).toLong(),
        loadAverage = listOf(0.42, 0.37, 0.31), systemProduct = null, eccMemory = false,
    )
    private val pools = listOf(
        Pool(1, "tank", "ONLINE", true, false, null, 32_000_000_000_000, 19_500_000_000_000, null, null, "SCRUB", "FINISHED", 100.0, 0, listOf("sda", "sdb")),
        Pool(2, "fast", "ONLINE", true, false, null, 2_000_000_000_000, 1_760_000_000_000, null, null, null, null, null, null, listOf("nvme0n1")),
    )
    private val live: LiveStats = run {
        var l = LiveStats()
        repeat(40) { i ->
            l = l.add(RealtimeStats(
                cpuPercent = 2.2 + 1.8 * sin(i / 3.0), cpuTempC = 46.0,
                memoryTotal = (125.5 * gib).toLong(), memoryAvailable = (8.7 * gib).toLong(), arcSize = (100.2 * gib).toLong(),
                netRxBytesPerSec = 7200.0 + 5000 * sin(i / 4.0).coerceAtLeast(0.0), netTxBytesPerSec = 1100.0,
                cpuCores = List(8) { c -> (c * 3 % 7).toDouble() },
            ))
        }
        l
    }
    private val data = DashboardData(
        loading = false, system = system, pools = pools,
        apps = AppsSummary(total = 14, running = 12, updates = 3, problems = 0),
        alerts = AlertsSummary(active = 1, worst = Health.WARNING, latest = AlertItem("1", "WARNING", "Pool fast is 88% full.", null, null, false, false)),
        hottestDisk = "sda" to 38.0,
        protection = app.truenascompanion.ProtectionSamples.healthySummary,
    )

    @Composable private fun Dashboard() = Column(Modifier.fillMaxSize().dashboardHeaderGlow()) {
        Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) {
            CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                DashboardTitle("homenas", "Live", Health.HEALTHY, live = true)
            }
        }
        Box(Modifier.weight(1f)) { WidgetGrid(WidgetType.entries.map { WidgetConfig(it) }, data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {}) }
    }

    private fun cert(id: Int, name: String, days: Long, sans: List<String>, issuer: String, kind: CertKind = CertKind.CERTIFICATE, acme: Boolean = false, self: Boolean = false) = NasCertificate(
        id, name, kind, sans.firstOrNull(), sans.map { "DNS:$it" }, issuer, self, now - 60 * day, if (kind == CertKind.CSR) null else now + days * day + 3_600_000L,
        days < 0, if (acme) "RSA" else "EC", if (acme) 2048 else 256, "SHA256", "3A:9F:12:C4:7B:0E:55:81:D2:6A:9C:44:10:EF:2B:73",
        acme, if (acme) "https://acme-v02.api.letsencrypt.org/directory" else null, if (acme) 10 else null, false, true, "/CN=${sans.firstOrNull()}",
    )
    private val certList = listOf(
        cert(1, "nas_letsencrypt", 52, listOf("nas.example.com", "files.example.com"), "R11", acme = true),
        cert(2, "truenas_default", 9, listOf("localhost"), "localhost", self = true),
        cert(3, "proxy_internal", -3, listOf("proxy.home.example"), "Example Home CA"),
        cert(4, "backup_target", 300, listOf("backup.example.net"), "Example Home CA"),
        cert(5, "Example_Home_CA", 2900, listOf("Example Home CA"), "Example Home CA", kind = CertKind.CA, self = true),
    )
    private val certs = CertsData(certs = certList, uiCertId = 1, uiChoices = mapOf(1 to "nas_letsencrypt", 2 to "truenas_default"))

    // --- tablets ---

    @Test @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun tablet840TwoPane() = shot("tablet_840_two_pane", WindowLayout(840, 900)) {
        Row(Modifier.fillMaxSize()) {
            AppNavRail("system", alertCount = 1) {}
            ListDetailFrame(
                list = { Column { Bar("Certificates", back = true); CertificatesContent(certs, now, 14, tab = 0, onTab = {}, highlight = "nas_letsencrypt", onAction = {}) } },
                detail = { Column(Modifier.padding(top = 16.dp)) { CertDetailContent(certList[0], now, 14, isWebUi = true) } },
                hint = "Pick a certificate to see its details here.",
            )
        }
    }

    @Test @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun tablet840Placeholder() = shot("tablet_840_list_only", WindowLayout(840, 900), dark = false) {
        Row(Modifier.fillMaxSize()) {
            AppNavRail("system") {}
            ListDetailFrame(
                list = { Column { Bar("Certificates", back = true); CertificatesContent(certs, now, 14, tab = 0, onTab = {}, onAction = {}) } },
                detail = null, hint = "Pick a certificate to see its details here.",
            )
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tablet1280Dashboard() = shot("tablet_1280_dashboard", WindowLayout(1280, 800)) {
        Row(Modifier.fillMaxSize()) {
            AppNavDrawer("dashboard", alertCount = 1, serverName = "homenas") {}
            Box(Modifier.weight(1f)) { Dashboard() }
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tablet1280Hub() = shot("tablet_1280_system_hub", WindowLayout(1280, 800), dark = false) {
        Row(Modifier.fillMaxSize()) {
            AppNavDrawer("system", serverName = "homenas") {}
            Column(Modifier.weight(1f)) {
                Bar("System")
                SystemHubContent(HubHeader("homenas", "nas.example.com", "25.10.4", 1_036_800, online = true), hubSubtitles(HubSummary(connected = true, serverCount = 2)), onOpen = {}, onSwitchServer = {})
            }
        }
    }

    @Test @Config(qualifiers = "w800dp-h360dp-xxhdpi")
    fun phoneLandscape() = shot("phone_landscape", WindowLayout(800, 360)) {
        Row(Modifier.fillMaxSize()) {
            AppNavRail("dashboard", alertCount = 1) {}
            Box(Modifier.weight(1f)) { Dashboard() }
        }
    }

    // --- Wake-on-LAN ---

    @Composable private fun ServerCard(name: String, host: String, reach: Reach, wake: WakeState?) = ElevatedSection(onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Dns)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReachDot(reach); Spacer(Modifier.width(8.dp)); Text(name, style = MaterialTheme.typography.titleMedium)
                }
                Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {}) { Icon(Icons.Rounded.Edit, "Edit") }
        }
        if (wake != null) { Spacer(Modifier.height(8.dp)); WakeAction(wake, onWake = {}, compact = true) }
    }

    @Test fun wakeServers() = shot("wol_servers", WindowLayout.PHONE) {
        Column {
            Bar("Your servers", back = true)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerCard("Home NAS", "nas.example.com", Reach.OFFLINE, WakeState.Waking(now - 23_000))
                ServerCard("Backup box", "backup.example.net", Reach.ONLINE, WakeState.Online)
                ServerCard("Office", "office.example.org", Reach.OFFLINE, WakeState.Idle)
            }
        }
    }

    @Test fun wakeFailureCard() = shot("wol_connection_lost", WindowLayout.PHONE, dark = false) {
        ConnectionFailurePanel(
            serverName = "Home NAS", detail = "The NAS didn't answer at nas.example.com. It may be switched off or asleep.",
            onQuit = {}, onCheckConfig = {}, onRetry = {}, onSwitchServer = {},
            wake = { WakeAction(WakeState.Idle, onWake = {}) },
        )
    }

    @Test @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun wakeSetup() = shot("wol_setup", WindowLayout.PHONE) {
        Column {
            Bar("Wake-on-LAN", back = true)
            WakeSetupContent(
                WakeForm(
                    loaded = true, serverName = "Home NAS", mac = "3c:ec:ef:12:34:56", interfaceName = "enp3s0", broadcast = "192.168.1.255",
                    candidates = listOf(
                        WakeOnLan.Candidate("enp3s0", "3c:ec:ef:12:34:56", "192.168.1.255", "192.168.1.20", 24),
                        WakeOnLan.Candidate("enp4s0", "3c:ec:ef:12:34:57", null, null),
                    ),
                    interfacesNote = "",
                ),
            )
        }
    }
}
