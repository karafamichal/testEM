package com.ksjd.testem.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.stream.JsonReader
import com.ksjd.testem.CpStopSuggestion
import com.ksjd.testem.SK_ZONE
import com.ksjd.testem.TimetableConnection
import com.ksjd.testem.TimetableSegment
import com.ksjd.testem.hub.HubClient
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 2.17.0: timetables kept on the phone, per region (kraj), picked on a map in Settings. When emHUB can't be reached the
 * planner searches them instead: direct buses and ones with one change, by the timetable only. They update by themselves
 * (a daily check of emHUB's timetable version) and cover the next three weeks.
 */
object OfflineTimetables {
    data class Region(val id: String, val name: String, val south: Double, val west: Double, val north: Double, val east: Double)

    /** A region's timetable as kept: stations and trips (stops as station indexes, departures in seconds of the day). */
    class Pack(
        val version: Long, val from: LocalDate,
        val ids: Array<String>, val names: Array<String>,
        val lines: Array<String>, val headsigns: Array<String>, val operators: Array<String>,
        val stops: Array<IntArray>, val times: Array<IntArray>, val days: IntArray
    ) {
        /** Each station's trips: (trip, its stop number there). */
        val at: Map<Int, List<Pair<Int, Int>>> by lazy {
            val m = HashMap<Int, MutableList<Pair<Int, Int>>>()
            stops.forEachIndexed { t, st -> st.forEachIndexed { k, s -> m.getOrPut(s) { mutableListOf() }.add(t to k) } }
            m
        }
        fun runs(trip: Int, date: LocalDate): Boolean {
            val d = (date.toEpochDay() - from.toEpochDay()).toInt()
            return d in 0..30 && days[trip] and (1 shl d) != 0
        }
    }

    private var dir: File? = null
    private val packs = HashMap<String, Pack>()
    /** Regions kept on the phone: id to version. */
    val kept = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun init(context: Context) {
        dir = File(context.filesDir, "offline").apply { mkdirs() }
        kept.value = dir!!.listFiles { f -> f.name.endsWith(".json") }.orEmpty().associate { f ->
            f.name.removeSuffix(".json") to (f.parentFile?.let { File(it, f.name + ".v") }?.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L)
        }
    }

    fun regions(): Pair<Long, List<Region>> {
        val root = HubClient.timetable("offline", emptyMap())
        return root.get("version").asLong to root.getAsJsonArray("regions").map { it.asJsonObject }.map { r ->
            val b = r.getAsJsonArray("bbox").map { it.asDouble }
            Region(r.get("id").asString, r.get("name").asString, b[0], b[1], b[2], b[3])
        }
    }

    /** Downloads [id] (about 0.5 MB compressed) and keeps it; replaces an older one. */
    fun download(id: String) {
        val folder = dir ?: return
        require(Regex("[a-z]{2}").matches(id))
        val tmp = File(folder, "$id.json.tmp")
        HubClient.timetableToFile("offline/region", mapOf("id" to id), tmp)
        val pack = read(tmp) // a broken download never replaces a good one
        tmp.renameTo(File(folder, "$id.json"))
        File(folder, "$id.json.v").writeText(pack.version.toString())
        synchronized(packs) { packs[id] = pack }
        kept.value = kept.value + (id to pack.version)
    }

    fun remove(id: String) {
        val folder = dir ?: return
        File(folder, "$id.json").delete()
        File(folder, "$id.json.v").delete()
        synchronized(packs) { packs.remove(id) }
        kept.value = kept.value - id
    }

    /** Kept regions downloaded again when emHUB has a newer timetable (the daily worker; also after a failed day). */
    fun updateAll() {
        if (kept.value.isEmpty()) return
        val (version, _) = regions()
        kept.value.filterValues { it != version }.keys.forEach { runCatching { download(it) } }
    }

    fun sizeOf(id: String): Long = dir?.let { File(it, "$id.json").length() } ?: 0L

    private fun pack(id: String): Pack? = synchronized(packs) {
        packs[id] ?: dir?.let { File(it, "$id.json") }?.takeIf { it.exists() }?.let { f -> runCatching { read(f) }.getOrNull() }?.also { packs[id] = it }
    }

    /** Streams the pack (a few MB) into plain arrays: far less memory than a JSON tree. */
    private fun read(file: File): Pack = JsonReader(file.bufferedReader()).use { r ->
        var version = 0L
        var from = LocalDate.now(SK_ZONE)
        val ids = ArrayList<String>(); val names = ArrayList<String>()
        val lines = ArrayList<String>(); val heads = ArrayList<String>(); val ops = ArrayList<String>()
        val stops = ArrayList<IntArray>(); val times = ArrayList<IntArray>(); val days = ArrayList<Int>()
        fun ints(): IntArray { val out = ArrayList<Int>(); r.beginArray(); while (r.hasNext()) out.add(r.nextInt()); r.endArray(); return out.toIntArray() }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "version" -> version = r.nextLong()
                "from" -> from = LocalDate.parse(r.nextString())
                "stations" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); ids.add(r.nextString()); names.add(r.nextString()); r.skipValue(); r.skipValue(); r.endArray() }; r.endArray() }
                "trips" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        lines.add(r.nextString()); heads.add(r.nextString()); ops.add(r.nextString())
                        stops.add(ints()); times.add(ints())
                        days.add(ints().fold(0) { acc, d -> if (d in 0..30) acc or (1 shl d) else acc })
                        r.endArray()
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        Pack(version, from, ids.toTypedArray(), names.toTypedArray(), lines.toTypedArray(), heads.toTypedArray(), ops.toTypedArray(),
            stops.toTypedArray(), times.toTypedArray(), days.toIntArray())
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The pack's stations for a planner field: the stop picked (its id), else the same name. */
    private fun stationsOf(p: Pack, pick: CpStopSuggestion?, text: String): Set<Int> {
        val byId = pick?.value?.let { id -> p.ids.indexOf(id).takeIf { it >= 0 } }
        if (byId != null) return setOf(byId)
        val want = fold(pick?.selectedText ?: text).takeIf { it.isNotBlank() } ?: return emptySet()
        return p.names.indices.filter { fold(p.names[it]) == want }.toSet()
    }

    /**
     * Up to [limit] connections leaving from [startS] (epoch seconds), today and tomorrow, with any number of changes of
     * at least [changeMin] minutes: each the earliest arrival (Connection Scan), the next searched from a minute after
     * the one before left. Null when no kept region has both stops.
     */
    fun search(from: CpStopSuggestion?, fromText: String, to: CpStopSuggestion?, toText: String, startS: Long,
               changeMin: Int, limit: Int = 5): List<TimetableConnection>? {
        val p = kept.value.keys.mapNotNull { pack(it) }.firstOrNull { stationsOf(it, from, fromText).isNotEmpty() && stationsOf(it, to, toText).isNotEmpty() }
            ?: return null
        return searchPack(p, stationsOf(p, from, fromText), stationsOf(p, to, toText), startS, changeMin, limit)
    }

    internal fun searchPack(p: Pack, a: Set<Int>, b: Set<Int>, startS: Long, changeMin: Int, limit: Int = 5): List<TimetableConnection> {
        val day0 = Instant.ofEpochSecond(startS).atZone(SK_ZONE).toLocalDate()
        val hops = hops(p, day0)
        val out = ArrayList<List<Leg>>()
        var at = startS
        while (out.size < limit) {
            val legs = scan(p, hops, a, b, at, changeMin * 60) ?: break
            out.add(legs)
            at = departure(p, legs.first()) + 60
        }
        return out.map { connection(p, it) }
    }

    private data class Leg(val trip: Int, val on: Int, val off: Int, val date: LocalDate)

    private fun departure(p: Pack, l: Leg) = l.date.atStartOfDay(SK_ZONE).toEpochSecond() + p.times[l.trip][l.on]
    private fun arrival(p: Pack, l: Leg) = l.date.atStartOfDay(SK_ZONE).toEpochSecond() + p.times[l.trip][l.off]

    /** Every stop-to-stop hop of [day] and the day after, by departure: (departure s, arrival s, trip, its stop number, day 0/1). */
    private class Hops(val dep: LongArray, val arr: LongArray, val trip: IntArray, val k: IntArray, val day: IntArray, val first: LocalDate)

    private val hopsCache = HashMap<Pair<Pack, LocalDate>, Hops>()

    private fun hops(p: Pack, day: LocalDate): Hops = synchronized(hopsCache) {
        hopsCache.getOrPut(p to day) {
            hopsCache.clear() // ponytail: one day's hops kept (a few MB); per region and day if people switch a lot
            val rows = ArrayList<LongArray>()
            for (d in 0..1) {
                val date = day.plusDays(d.toLong())
                val base = date.atStartOfDay(SK_ZONE).toEpochSecond()
                for (t in p.stops.indices) {
                    if (!p.runs(t, date)) continue
                    val times = p.times[t]
                    for (k in 0 until times.size - 1) rows.add(longArrayOf(base + times[k], base + times[k + 1], t.toLong(), k.toLong(), d.toLong()))
                }
            }
            rows.sortBy { it[0] }
            Hops(LongArray(rows.size) { rows[it][0] }, LongArray(rows.size) { rows[it][1] }, IntArray(rows.size) { rows[it][2].toInt() },
                IntArray(rows.size) { rows[it][3].toInt() }, IntArray(rows.size) { rows[it][4].toInt() }, day)
        }
    }

    /**
     * Connection Scan: the earliest arrival at [b] leaving [a] from [startS]. A rider gets on a bus at a stop they reached
     * [change] seconds before (at once where they start), and stays on it as long as it helps.
     */
    private fun scan(p: Pack, h: Hops, a: Set<Int>, b: Set<Int>, startS: Long, change: Int): List<Leg>? {
        val reach = HashMap<Int, Long>() // stop -> earliest arrival
        val via = HashMap<Int, IntArray>() // stop -> [hop got on, hop got off] of the bus that brought the rider there
        val boarded = HashMap<Long, Int>() // (trip, day) -> the hop where the rider got on
        a.forEach { reach[it] = startS }
        var best = Long.MAX_VALUE
        var from = h.dep.binarySearch(startS).let { if (it < 0) -it - 1 else it }
        while (from > 0 && h.dep[from - 1] >= startS) from--
        for (c in from until h.dep.size) {
            if (h.dep[c] >= best) break
            val t = h.trip[c]
            val key = t.toLong() * 2 + h.day[c]
            val stop = p.stops[t][h.k[c]]
            val on = boarded[key] ?: run {
                val r = reach[stop] ?: return@run null
                if (r + (if (stop in a) 0 else change) <= h.dep[c]) c.also { boarded[key] = it } else null
            } ?: continue
            val next = p.stops[t][h.k[c] + 1]
            if (h.arr[c] < (reach[next] ?: Long.MAX_VALUE) && next !in a) {
                reach[next] = h.arr[c]
                via[next] = intArrayOf(on, c)
                if (next in b) best = minOf(best, h.arr[c])
            }
        }
        val end = b.filter { reach[it] != null && it in via }.minByOrNull { reach[it]!! } ?: return null
        val legs = ArrayList<Leg>()
        var stop = end
        while (stop !in a) {
            val (on, off) = via[stop]!!.let { it[0] to it[1] }
            legs.add(0, Leg(h.trip[on], h.k[on], h.k[off] + 1, h.first.plusDays(h.day[on].toLong())))
            stop = p.stops[h.trip[on]][h.k[on]]
            if (legs.size > 8) return null // a loop would mean a bug; better nothing than nonsense
        }
        return legs
    }

    private fun connection(p: Pack, legs: List<Leg>): TimetableConnection {
        fun clock(s: Long) = Instant.ofEpochSecond(s).atZone(SK_ZONE).let { "%d:%02d".format(it.hour, it.minute) }
        val segments = legs.map { l ->
            val line = p.lines[l.trip]
            TimetableSegment(
                line = if (line.any { it.isLetter() } && line.contains(' ')) line else "Bus $line",
                operatorName = p.operators[l.trip],
                departureTime = clock(departure(p, l)), departureStop = p.names[p.stops[l.trip][l.on]],
                arrivalTime = clock(arrival(p, l)), arrivalStop = p.names[p.stops[l.trip][l.off]],
                serviceDate = l.date.toString()
            )
        }
        val minutes = ((arrival(p, legs.last()) - departure(p, legs.first())) / 60).toInt()
        return TimetableConnection(
            id = "offline:" + legs.joinToString("|") { "${it.trip}@${it.date}" },
            departureTime = segments.first().departureTime, arrivalTime = segments.last().arrivalTime,
            totalDuration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min",
            segments = segments
        )
    }

    /** The daily update of kept regions (only over the network; does nothing with none kept). */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (kept.value.isEmpty()) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OfflineUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private const val WORK = "offline_update"
}

class OfflineUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        OfflineTimetables.init(applicationContext)
        OfflineTimetables.updateAll()
    }.fold({ Result.success() }, { Result.retry() })
}
