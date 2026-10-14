package app.truenascompanion.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.truenascompanion.wearlink.WearStatus
import com.google.common.util.concurrent.ListenableFuture

/**
 * Status tile: health dot and word, server name, "2 pools healthy · 1 alert", CPU and disk temperature, updated time.
 * Tapping it opens the app. It is refreshed when the phone sends a new status (no polling on the watch).
 */
class StatusTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = WearStore.load(this)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(StatusTileLayout.layout(s, packageName)))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    private fun <T : Any> done(v: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { it.set(v); "ytn-tile" }

    companion object { const val RES_VERSION = "1" }
}

/** The tile's layout (no Android context needed, so it can be unit tested). */
object StatusTileLayout {
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 1) = LayoutElementBuilders.Text.Builder()
        .setText(t)
        .setMaxLines(maxLines)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
        )
        .build()

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    fun lines(s: WearStatus, now: Long = System.currentTimeMillis()): List<String> = listOfNotNull(
        s.serverName ?: "YTN",
        WearColors.healthWord(s),
        s.summary(),
        listOfNotNull(s.cpuPercent?.let { "CPU $it%" }, s.tempC?.let { "$it °C" }).joinToString(" · ").takeIf { s.online && it.isNotEmpty() },
        updatedText(s.updatedAt, now),
    )

    fun layout(s: WearStatus, packageName: String, now: Long = System.currentTimeMillis()): LayoutElementBuilders.LayoutElement {
        val l = lines(s, now)
        val color = WearColors.of(s)
        val dot = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(14f)).setHeight(dp(14f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(7f)).build()).build())
                    .build(),
            ).build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(dot)
            .addContent(spacer(4f))
            .addContent(text(l[0], 14f, 0xFFFFFFFF.toInt()))
            .addContent(text(l[1], 24f, color, bold = true))
            .addContent(spacer(2f))
            .addContent(text(l[2], 13f, 0xFFE0E0E0.toInt(), maxLines = 2))
        l.drop(3).forEachIndexed { i, line ->
            column.addContent(spacer(2f)).addContent(text(line, 12f, if (i == l.size - 4) 0xFF9AA0A6.toInt() else 0xFFE0E0E0.toInt()))
        }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearMainActivity::class.java.name).build(),
                ).build(),
            ).build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()
    }
}
