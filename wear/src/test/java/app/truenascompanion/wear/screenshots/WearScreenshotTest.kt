package app.truenascompanion.wear.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.truenascompanion.wear.AlertListScreen
import app.truenascompanion.wear.StatusScreen
import app.truenascompanion.wear.StatusTileLayout
import app.truenascompanion.wear.WearColors
import app.truenascompanion.wearlink.WearAlert
import app.truenascompanion.wearlink.WearHealth
import app.truenascompanion.wearlink.WearStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.12.0 watch app previews (example data). The tile is drawn from the same lines the real tile shows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi", application = android.app.Application::class)
class WearScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private val now = System.currentTimeMillis()
    private val status = WearStatus(
        serverName = "homenas", health = WearHealth.WARNING, poolsHealthy = 2, poolsTotal = 2, alertCount = 2,
        cpuPercent = 3, tempC = 38, online = true, canWake = true, updatedAt = now - 4 * 60_000,
        alerts = listOf(WearAlert("1", "WARNING", "3 app updates available"), WearAlert("2", "INFO", "Scrub finished on tank")),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        repeat(4) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(File(dir, "v1120_watch_$name.png").absolutePath)
    }

    @Test fun app() = shot("app") { StatusScreen(status, null, {}, {}, {}, now) }

    @Test fun alerts() = shot("alerts") { AlertListScreen(status, {}, {}) }

    @Test fun tile() = shot("tile") {
        val l = StatusTileLayout.lines(status, now)
        val color = Color(WearColors.of(status))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Spacer(Modifier.height(4.dp))
            Text(l[0], fontSize = 14.sp, color = Color.White)
            Text(l[1], fontSize = 24.sp, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(l[2], fontSize = 13.sp, color = Color(0xFFE0E0E0), textAlign = TextAlign.Center, maxLines = 2)
            l.drop(3).forEachIndexed { i, line ->
                Spacer(Modifier.height(2.dp))
                Text(line, fontSize = 12.sp, color = if (i == l.size - 4) Color(0xFF9AA0A6) else Color(0xFFE0E0E0))
            }
        }
    }
}
