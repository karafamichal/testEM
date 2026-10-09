package com.ksjd.testem.wear

import android.content.Context
import android.util.Base64
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Paths shared with the phone app (live/WatchSync.kt). */
const val FOLLOW_PATH = "/plan-ride/follow"
const val STOP_PATH = "/plan-ride/stop"

/** The bus followed on the phone, as the phone's home card shows it (sent by WatchSync). */
data class WatchFollow(
    val line: String,
    val destination: String,
    /** When the countdown reaches zero: the departure from the rider's stop, or the next stop once on board. */
    val targetMs: Long,
    val caption: String,
    val delay: String,
    val from: String,
    val to: String,
    val hops: Int,
    val position: Float,
    val catchLine: String,
    val live: Boolean,
    val onBoard: Boolean,
    val stops: List<Stop> = emptyList(),
    /** Index of the next stop the bus reaches; the rider's boarding and alighting stops (-1 unknown). */
    val nextIndex: Int = 0,
    val boardIndex: Int = -1,
    val alightIndex: Int = -1,
    /** The journey's next buses. */
    val legs: List<Leg> = emptyList(),
    /** This trip's alerts as the phone showed them (they reach the watch once, bridged from the phone). */
    val alerts: List<Alert> = emptyList(),
    /** Set on the phone (Account → Watch): the countdown on the watch face, and how many next stops to list. */
    val ongoing: Boolean = true,
    val nextStops: Int = 3,
    /** The phone notification's buttons right now ("Bus is here"…), pressed through ACTION_PATH by index. */
    val buttons: List<String> = emptyList(),
    /** How late the bus is in seconds (null: not known, timetable only): the countdown and ring take its colour. */
    val delaySeconds: Int? = null
) {
    data class Stop(val name: String, val atMs: Long, val passed: Boolean, val platform: String)
    data class Leg(val line: String, val destination: String, val from: String, val atMs: Long)
    data class Alert(val atMs: Long, val title: String, val body: String, val key: String = "")

    companion object {
        fun from(map: DataMap) = WatchFollow(
            line = map.getString("line", ""),
            destination = map.getString("dest", ""),
            targetMs = map.getLong("target", 0L),
            caption = map.getString("caption", ""),
            delay = map.getString("delay", ""),
            from = map.getString("from", ""),
            to = map.getString("to", ""),
            hops = map.getInt("hops", 0),
            position = map.getFloat("position", 0f),
            catchLine = map.getString("catch", ""),
            live = map.getBoolean("live", false),
            onBoard = map.getBoolean("onBoard", false),
            stops = map.getDataMapArrayList("stops").orEmpty().map {
                Stop(it.getString("name", ""), it.getLong("at", 0L), it.getBoolean("passed", false), it.getString("platform", ""))
            },
            nextIndex = map.getInt("next", 0),
            boardIndex = map.getInt("board", -1),
            alightIndex = map.getInt("alight", -1),
            legs = map.getDataMapArrayList("legs").orEmpty().map {
                Leg(it.getString("line", ""), it.getString("dest", ""), it.getString("from", ""), it.getLong("at", 0L))
            },
            alerts = map.getDataMapArrayList("alerts").orEmpty().map {
                Alert(it.getLong("at", 0L), it.getString("title", ""), it.getString("body", ""), it.getString("key", ""))
            },
            ongoing = map.getBoolean("ongoing", true),
            nextStops = map.getInt("nextStops", 3),
            buttons = map.getStringArray("buttons")?.toList().orEmpty(),
            delaySeconds = if (map.containsKey("delaySec")) map.getInt("delaySec") else null
        )
    }
}

/** "5 min", "now", or the time when it's far off: the countdown as the phone writes it. */
fun countdown(context: Context, targetMs: Long, nowMs: Long): String {
    if (targetMs <= 0L) return ""
    val minutes = ceil((targetMs - nowMs) / 60_000.0).toInt()
    return when {
        minutes <= 0 -> context.getString(R.string.now)
        minutes > 90 -> clock(targetMs)
        else -> context.getString(R.string.minutes, minutes)
    }
}

fun clock(ms: Long): String = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(ms))

/** The followed bus on this watch: kept on disk so the tile and the app have it after a restart. */
object FollowStore {
    private val _state = MutableStateFlow<WatchFollow?>(null)
    val state: StateFlow<WatchFollow?> = _state
    private var loaded = false

    fun current(context: Context): WatchFollow? {
        if (!loaded) {
            loaded = true
            _state.value = context.prefs().getString("follow", null)?.let {
                runCatching { WatchFollow.from(DataMap.fromByteArray(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull()
            }
        }
        return _state.value
    }

    /** New data from the phone (null: following stopped): the app, the ongoing activity and the tile follow it. */
    fun update(context: Context, map: DataMap?) {
        loaded = true
        context.prefs().edit().apply {
            if (map == null) remove("follow") else putString("follow", Base64.encodeToString(map.toByteArray(), Base64.NO_WRAP))
        }.apply()
        val follow = map?.let(WatchFollow::from)
        _state.value = follow
        map?.getString("lang", "")?.let { applyLanguage(context, it) }
        if (follow == null || !follow.ongoing) OngoingFollow.hide(context) else OngoingFollow.show(context, follow)
        // "Get off next" and "Leave now": announced here with their own vibrations (the phone keeps them to itself).
        listOf("alight" to OngoingFollow.Buzz.GetOff, "catch_leave" to OngoingFollow.Buzz.Leave).forEach { (key, buzz) ->
            follow?.alerts?.lastOrNull { it.key == key }?.let { alert ->
                val prefs = context.prefs()
                if (prefs.getLong("${key}_at", 0L) != alert.atMs) {
                    prefs.edit().putLong("${key}_at", alert.atMs).apply()
                    OngoingFollow.alert(context, buzz, alert.title, alert.body)
                }
            }
        }
        refreshTiles(context)
    }

    private fun Context.prefs() = getSharedPreferences("follow", Context.MODE_PRIVATE)
}
