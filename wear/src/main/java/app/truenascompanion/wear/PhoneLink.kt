package app.truenascompanion.wear

import android.content.Context
import app.truenascompanion.wearlink.WearLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Messages to the phone (refresh, wake, acknowledge) and a read of the last status DataItem. */
object PhoneLink {
    enum class Result { SENT, NO_PHONE, FAILED }

    suspend fun send(context: Context, path: String, data: ByteArray = ByteArray(0)): Result = runCatching {
        val nodes = Wearable.getCapabilityClient(context).getCapability(WearLink.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return Result.NO_PHONE
        Wearable.getMessageClient(context).sendMessage(node.id, path, data).await()
        Result.SENT
    }.getOrDefault(Result.FAILED)

    /** Picks up a status the phone put while the listener wasn't running. */
    suspend fun pull(context: Context) = runCatching {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            items.firstOrNull { it.uri.path == WearLink.STATUS_PATH }?.data?.let { bytes -> WearLink.decode(bytes)?.let { WearStore.save(context, it) } }
        } finally {
            items.release()
        }
    }
}
