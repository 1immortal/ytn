package app.truenascompanion.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.truenascompanion.wearlink.WearStatus

/** Complication: "OK", the alert count, or "Off" (short text); the one-line summary (long text). */
class StatusComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = build(request.complicationType, WearStore.load(this))

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, WearStatus(serverName = "NAS", online = true, poolsHealthy = 2, poolsTotal = 2, health = app.truenascompanion.wearlink.WearHealth.HEALTHY), tap = false)

    private fun build(type: ComplicationType, s: WearStatus, tap: Boolean = true): ComplicationData? {
        val desc = PlainComplicationText.Builder("${s.serverName ?: "NAS"}: ${WearColors.healthWord(s)}, ${s.summary()}").build()
        val pi = if (tap) PendingIntent.getActivity(this, 0, Intent(this, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE) else null
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(s.shortText()).build(), desc)
                .setTitle(PlainComplicationText.Builder("NAS").build())
                .apply { pi?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(s.summary()).build(), desc)
                .setTitle(PlainComplicationText.Builder(s.serverName ?: "YTN").build())
                .apply { pi?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }
}
