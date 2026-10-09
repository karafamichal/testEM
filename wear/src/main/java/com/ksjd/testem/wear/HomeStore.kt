package com.ksjd.testem.wear

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.Base64
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val HOME_PATH = "/plan-ride/home"
const val REFRESH_PATH = "/plan-ride/refresh"
const val ACTION_PATH = "/plan-ride/action"
const val START_PATH = "/plan-ride/start"

/** Everything besides the followed bus, from the phone (WatchSync.sendHome): trips to start, credit, departures. */
data class WatchHome(
    val updatedMs: Long,
    val trips: List<Trip>,
    val creditSadzv: Double?,
    val creditIds: Double?,
    val favourites: List<Board>,
    val nearby: List<Board>,
    /** The phone could tell where it is (nearby departures need it). */
    val nearbyLocated: Boolean,
    /** The next connection on the route home set on the phone (Account → Watch), null without one. */
    val homeTrip: Trip? = null
) {
    /** A starred journey ("j:…") or a regular ride ("r:…") that can be started from the watch. */
    data class Trip(val id: String, val title: String, val sub: String, val lines: String, val atMs: Long)
    data class Board(val name: String, val meters: Int, val departures: List<Departure>)
    data class Departure(val line: String, val destination: String, val atMs: Long, val live: Boolean)

    companion object {
        fun from(map: DataMap) = WatchHome(
            updatedMs = map.getLong("updated", 0L),
            trips = map.getDataMapArrayList("trips").orEmpty().map(::trip),
            creditSadzv = if (map.containsKey("creditSadzv")) map.getDouble("creditSadzv") else null,
            creditIds = if (map.containsKey("creditIds")) map.getDouble("creditIds") else null,
            favourites = boards(map.getDataMapArrayList("favourites")),
            nearby = boards(map.getDataMapArrayList("nearby")),
            nearbyLocated = map.getBoolean("nearbyLocated", false),
            homeTrip = map.getDataMap("home")?.let { trip(it) }
        )

        private fun trip(it: DataMap) = Trip(it.getString("id", ""), it.getString("title", ""), it.getString("sub", ""), it.getString("lines", ""), it.getLong("at", 0L))

        private fun boards(list: List<DataMap>?) = list.orEmpty().map { b ->
            Board(b.getString("name", ""), b.getInt("meters", -1), b.getDataMapArrayList("deps").orEmpty().map {
                Departure(it.getString("line", ""), it.getString("dest", ""), it.getLong("at", 0L), it.getBoolean("live", false))
            })
        }
    }
}

/** The phone's "home" data on this watch, kept on disk for the tiles. */
object HomeStore {
    private val _state = MutableStateFlow<WatchHome?>(null)
    val state: StateFlow<WatchHome?> = _state
    private var loaded = false

    fun current(context: Context): WatchHome? {
        if (!loaded) {
            loaded = true
            _state.value = context.prefs().getString("home", null)?.let {
                runCatching { WatchHome.from(DataMap.fromByteArray(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull()
            }
        }
        return _state.value
    }

    fun update(context: Context, map: DataMap) {
        loaded = true
        context.prefs().edit().putString("home", Base64.encodeToString(map.toByteArray(), Base64.NO_WRAP)).apply()
        _state.value = WatchHome.from(map)
        applyLanguage(context, map.getString("lang", ""))
        refreshTiles(context)
    }

    private fun Context.prefs() = getSharedPreferences("home", Context.MODE_PRIVATE)
}

/** Ask the phone for fresh home data; [nearby]: with departures near the phone (the watch's GPS stays off). */
fun requestHome(context: Context, nearby: Boolean = false) = sendToPhone(context, REFRESH_PATH, if (nearby) "nearby" else "")

/** A message to the phone app; false when no phone is connected. */
fun sendToPhone(context: Context, path: String, payload: String = "", onDone: (Boolean) -> Unit = {}) {
    Wearable.getNodeClient(context).connectedNodes
        .addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, path, payload.toByteArray(Charsets.UTF_8)) }
            onDone(nodes.isNotEmpty())
        }
        .addOnFailureListener { onDone(false) }
}

/** The watch app speaks the phone app's language ("sk"/"en"), whatever the watch's own language is (Wear OS 4+). */
fun applyLanguage(context: Context, lang: String) {
    if (lang.isBlank() || Build.VERSION.SDK_INT < 33) return
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    if (manager.applicationLocales.toLanguageTags() != lang) manager.applicationLocales = LocaleList.forLanguageTags(lang)
}

fun money(value: Double): String = "%.2f €".format(value).replace('.', ',')
