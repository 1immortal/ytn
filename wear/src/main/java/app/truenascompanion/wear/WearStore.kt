package app.truenascompanion.wear

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.truenascompanion.wearlink.WearLink
import app.truenascompanion.wearlink.WearStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The last status the phone sent, kept on the watch so the tile and complication can answer at once. */
object WearStore {
    private const val PREFS = "ytn_wear"
    private const val KEY = "status"
    private val flow = MutableStateFlow<WearStatus?>(null)

    fun load(context: Context): WearStatus {
        flow.value?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return (WearLink.decode(raw?.toByteArray()) ?: WearStatus()).also { flow.value = it }
    }

    fun observe(context: Context): StateFlow<WearStatus?> { load(context); return flow }

    fun save(context: Context, s: WearStatus) {
        if (s.version > WearLink.VERSION + 5) return // a far newer phone format: keep the last one we understood
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, WearLink.encode(s).toString(Charsets.UTF_8)) }
        flow.value = s
        runCatching { TileService.getUpdater(context).requestUpdate(StatusTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, StatusComplicationService::class.java)).requestUpdateAll()
        }
    }
}
