package app.truenascompanion.wear

import android.content.Context
import app.truenascompanion.wearlink.WearLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** The Data Layer, behind an interface so tests (and phones without Google Play services) don't need it. */
interface WearTransport {
    suspend fun watchReachable(): Boolean
    suspend fun put(path: String, bytes: ByteArray, urgent: Boolean)
}

class PlayWearTransport(private val context: Context) : WearTransport {
    override suspend fun watchReachable(): Boolean =
        Wearable.getCapabilityClient(context).getCapability(WearLink.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()

    override suspend fun put(path: String, bytes: ByteArray, urgent: Boolean) {
        val req = PutDataRequest.create(path).setData(bytes)
        if (urgent) req.setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}
