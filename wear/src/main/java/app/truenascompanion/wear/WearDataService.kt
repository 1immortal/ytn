package app.truenascompanion.wear

import app.truenascompanion.wearlink.WearLink
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** Receives the status the phone puts (only the paired phone's YTN, same package and key, can write it). */
class WearDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WearLink.STATUS_PATH) {
                WearLink.decode(e.dataItem.data)?.let { WearStore.save(this, it) }
            }
        }
    }
}
