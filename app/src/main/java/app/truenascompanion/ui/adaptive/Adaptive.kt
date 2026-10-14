package app.truenascompanion.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How the app navigates at the current window size (1.12.0). */
enum class NavStyle { BOTTOM_BAR, RAIL, DRAWER }

/**
 * 1.12.0: layout decisions from the Material 3 window size class. Pure (unit tested), built from the size-class
 * buckets so a resize, fold or rotation only changes the layout, never the screen's state.
 *
 * - compact width (phones in portrait): bottom bar, one pane;
 * - landscape phones (compact height): navigation rail, one pane, so content isn't squeezed under a bottom bar;
 * - medium width (small tablets, unfolded foldables, big phones in landscape): rail and list-detail two panes;
 * - expanded width: rail, two panes; 1200 dp and wider: a permanent drawer.
 */
@Stable
data class WindowLayout(val widthDp: Int, val heightDp: Int) {
    val compactWidth: Boolean get() = widthDp < MEDIUM
    val compactHeight: Boolean get() = heightDp < HEIGHT_MEDIUM
    val expandedWidth: Boolean get() = widthDp >= EXPANDED

    val navStyle: NavStyle get() = when {
        widthDp >= LARGE && !compactHeight -> NavStyle.DRAWER
        !compactWidth || compactHeight -> NavStyle.RAIL
        else -> NavStyle.BOTTOM_BAR
    }

    /** List-detail screens show both panes (not on landscape phones: too short for two scrolling lists). */
    val twoPane: Boolean get() = !compactWidth && !compactHeight

    /** Width a single-pane screen gets before the rest becomes page background. */
    val singlePaneMaxWidth: Dp get() = 840.dp

    /** Width of the whole content area on wide screens (two panes, the dashboard grid, the System hub). */
    val widePaneMaxWidth: Dp get() = 1600.dp

    /** Dashboard grid columns: half-width cards span 1, full-width cards 2 (so 2 = the phone layout). */
    fun dashboardColumns(contentWidthDp: Int): Int = when {
        contentWidthDp >= 1100 -> 6
        contentWidthDp >= 700 -> 4
        else -> 2
    }

    companion object {
        const val MEDIUM = 600
        const val EXPANDED = 840
        const val LARGE = 1200
        const val HEIGHT_MEDIUM = 480
        val PHONE = WindowLayout(400, 800)
    }
}

/** The current window's layout; screens read it with [LocalWindowLayout]. */
val LocalWindowLayout = compositionLocalOf { WindowLayout.PHONE }

@Composable
fun rememberWindowLayout(): WindowLayout {
    val wsc = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true).windowSizeClass
    val w = wsc.minWidthDp
    val h = wsc.minHeightDp
    return remember(w, h) { WindowLayout(w, h) }
}

/**
 * Detail pane for in-screen editors and sheets: on two-pane layouts, a [FullScreenEditor] or an adaptive sheet shows
 * up next to its list instead of covering it.
 */
@Stable
class DetailHost {
    private var owner: Any? by mutableStateOf(null)
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    fun show(token: Any, c: @Composable () -> Unit) { owner = token; content = c }
    fun clear(token: Any) { if (owner === token) { owner = null; content = null } }
}

val LocalDetailHost = staticCompositionLocalOf<DetailHost?> { null }

/**
 * Puts [content] into the surrounding two-pane frame's detail pane. Returns false when there is no such pane (phones,
 * landscape phones); the caller then shows its usual dialog or bottom sheet.
 */
@Composable
fun showInDetailPane(content: @Composable () -> Unit): Boolean {
    val host = LocalDetailHost.current ?: return false
    val latest by rememberUpdatedState(content)
    val token = remember { Any() }
    DisposableEffect(host, token) {
        host.show(token) { latest() }
        onDispose { host.clear(token) }
    }
    return true
}

/**
 * List-detail frame. One pane on phones ([detail] if there is one, else [list]); on two-pane layouts the list on the
 * left and, on the right, an editor opened from the list, else the navigated [detail], else a hint.
 */
@Composable
fun ListDetailFrame(list: @Composable () -> Unit, detail: (@Composable () -> Unit)?, hint: String) {
    val layout = LocalWindowLayout.current
    if (!layout.twoPane) {
        if (detail != null) detail() else list()
        return
    }
    val host = remember { DetailHost() }
    Row(Modifier.fillMaxSize().testTag(TWO_PANE_TAG)) {
        Box(Modifier.weight(if (layout.expandedWidth) 0.42f else 0.5f).fillMaxHeight()) {
            CompositionLocalProvider(LocalDetailHost provides host) { list() }
        }
        VerticalDivider()
        Box(Modifier.weight(if (layout.expandedWidth) 0.58f else 0.5f).fillMaxHeight()) {
            val c = host.content
            when {
                c != null -> c()
                detail != null -> detail()
                else -> DetailPlaceholder(hint)
            }
        }
    }
}

@Composable
fun DetailPlaceholder(hint: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ViewSidebar, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

const val TWO_PANE_TAG = "two_pane"

/**
 * Bottom sheet on phones, the detail pane on two-pane layouts (1.12.0). The pane version has a close button and
 * scrolls; Back closes it either way.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(onDismiss: () -> Unit, title: String? = null, skipPartiallyExpanded: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val inPane = showInDetailPane {
        androidx.activity.compose.BackHandler { onDismiss() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            // The content scrolls itself (as it does in the bottom sheet).
            Column(Modifier.weight(1f), content = content)
        }
    }
    if (!inPane) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
            content = content,
        )
    }
}

/** 1.12.0: tallest a list inside a dialog may get (landscape phones have about 360 dp of height in total). */
@Composable
fun dialogListMaxHeight(): Dp = if (LocalWindowLayout.current.compactHeight) 150.dp else 380.dp
