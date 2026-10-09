package com.ksjd.testem.live

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ksjd.testem.hub.HubClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Buses shared by a one-time code, after the sharer accepted (ShareTripDialog / EnterShareCodeDialog). Outgoing: the
 * people this phone shares its followed bus with; it sends them where it is while following (TripTrackerService).
 * Incoming: buses others share with this phone; their sharer's position shows on the bus map, and sending one's own
 * back is a switch there. Positions go only through emhub, which keeps the last one for a few minutes.
 */
object Shares {
    private const val TAG = "Shares"
    private const val SEND_EVERY_MS = 20_000L

    data class Outgoing(val id: String, val token: String, val name: String)
    data class Incoming(val claimId: String, val token: String, val label: String, val line: String, val tripNumber: Int, val lineId: Long)

    private val _outgoing = MutableStateFlow<List<Outgoing>>(emptyList())
    val outgoing: StateFlow<List<Outgoing>> = _outgoing
    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming
    /** The other side's last position, by share id / claim id. */
    private val _positions = MutableStateFlow<Map<String, HubClient.SharedPosition>>(emptyMap())
    val positions: StateFlow<Map<String, HubClient.SharedPosition>> = _positions
    private var loaded = false
    @Volatile private var lastSentMs = 0L

    private fun prefs(context: Context) = context.getSharedPreferences("shares", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _outgoing.value = runCatching { Gson().fromJson<List<Outgoing>>(p.getString("out", "[]"), object : TypeToken<List<Outgoing>>() {}.type) }.getOrNull().orEmpty()
        _incoming.value = runCatching { Gson().fromJson<List<Incoming>>(p.getString("in", "[]"), object : TypeToken<List<Incoming>>() {}.type) }.getOrNull().orEmpty()
    }

    private fun save(context: Context) = prefs(context).edit()
        .putString("out", Gson().toJson(_outgoing.value)).putString("in", Gson().toJson(_incoming.value)).apply()

    fun addOutgoing(context: Context, share: Outgoing) {
        load(context)
        _outgoing.value = _outgoing.value + share
        save(context)
    }

    fun addIncoming(context: Context, share: Incoming) {
        load(context)
        _incoming.value = _incoming.value.filter { it.claimId != share.claimId } + share
        save(context)
    }

    /** The incoming share for this bus, if it came by a code. */
    fun incomingFor(context: Context, ref: TripRef): Incoming? {
        load(context)
        return _incoming.value.lastOrNull { it.line == ref.line && it.tripNumber == ref.tripNumber && it.lineId == ref.lineId }
    }

    fun removeIncoming(context: Context, claimId: String) {
        _incoming.value = _incoming.value.filter { it.claimId != claimId }
        save(context)
    }

    /** Following ended: sharing ends with it (emhub is told, so the other side sees it stopped). */
    suspend fun stopOutgoing(context: Context) {
        load(context)
        val ended = _outgoing.value
        if (ended.isEmpty()) return
        _outgoing.value = emptyList()
        save(context)
        ended.forEach { share ->
            sockets.remove(share.id)?.close()
            runCatching { HubClient.answerShare(share.id, share.token, "cancel") }
        }
    }

    /** The sharer's live connections, one per person: positions out, theirs and "they left" in. */
    private val sockets = java.util.concurrent.ConcurrentHashMap<String, HubClient.ShareSocket>()

    private fun socketFor(context: Context, share: Outgoing): HubClient.ShareSocket = sockets.getOrPut(share.id) {
        HubClient.ShareSocket("owner", share.id, share.token,
            onStatus = { status, _, _ ->
                if (status == "cancelled" || status == "declined") {
                    sockets.remove(share.id)?.close()
                    _outgoing.value = _outgoing.value.filter { it.id != share.id }
                    save(context)
                }
            },
            onPosition = { p -> _positions.value = _positions.value + (share.id to p) }
        ).open()
    }

    /** A new position while following: to everyone this bus is shared with (over their sockets), at most every 20 s. */
    fun publish(context: Context, fix: Location) {
        load(context)
        val now = System.currentTimeMillis()
        if (_outgoing.value.isEmpty() || now - lastSentMs < SEND_EVERY_MS) return
        lastSentMs = now
        _outgoing.value.forEach { socketFor(context, it).send(fix.latitude, fix.longitude, fix.accuracy.toInt()) }
        Log.i(TAG, "position to ${_outgoing.value.size} shares")
    }

    /**
     * An incoming share's live connection (while its bus's map is open): the sharer's position as it moves, and
     * [onEnded] when they stop. Ours goes back with [HubClient.ShareSocket.send].
     */
    fun watchIncoming(share: Incoming, onEnded: () -> Unit): HubClient.ShareSocket =
        HubClient.ShareSocket("claim", share.claimId, share.token,
            onStatus = { status, _, _ -> if (status == "cancelled" || status == "declined") onEnded() },
            onPosition = { p -> _positions.value = _positions.value + (share.claimId to p) }
        ).open()
}
