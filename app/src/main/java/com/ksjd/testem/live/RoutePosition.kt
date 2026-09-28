package com.ksjd.testem.live

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** Where a GPS point lies along a trip's stops. Pure logic, no Android types. */
object RoutePosition {
    /** A GPS point further than this from every stop-to-stop line is not on the route. */
    const val BUS_GPS_MAX_M = 300.0
    /** Tighter for a rider's phone: they must be on the bus, not walking beside the road. */
    const val RIDER_GPS_MAX_M = 80.0
    /** Positions older than this are ignored (sadzv sometimes keeps positions for months). */
    const val FRESH_MS = 2 * 60_000L
    /** Share of a stop-to-stop hop a point may be off its straight line (see [snap]). */
    const val HOP_SLACK = 0.1

    /**
     * Fractional stop index (2.4 = 40 % of the way from stop 2 to stop 3) and the distance
     * to the route, or null when the point is too far from it. [expectedIndex] (from the
     * timetable and delay) breaks ties on routes that come back along the same street.
     */
    fun snap(
        lat: Double,
        lon: Double,
        coords: List<Pair<Double, Double>?>,
        maxMeters: Double,
        expectedIndex: Float? = null
    ): Pair<Float, Double>? {
        var best: Triple<Float, Double, Double>? = null // index, distance, cost
        val k = cos(Math.toRadians(lat))
        fun xy(p: Pair<Double, Double>) = ((p.second - lon) * k * 111_320.0) to ((p.first - lat) * 110_540.0)
        for (i in 0 until coords.size - 1) {
            val a = coords[i] ?: continue
            val b = coords[i + 1] ?: continue
            val (ax, ay) = xy(a)
            val (bx, by) = xy(b)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            // Stops are joined by straight lines, but roads bend: between intercity stops km apart
            // the road can run hundreds of metres off that line. Allow 10 % of the hop.
            if (d > maxOf(maxMeters, sqrt(len2) * HOP_SLACK)) continue
            val index = (i + t).toFloat()
            // Being two or more stops away from where the bus should be costs like 150 m per stop.
            val cost = d + (expectedIndex?.let { (abs(index - it) - 2f).coerceAtLeast(0f) * 150.0 } ?: 0.0)
            if (best == null || cost < best.third) best = Triple(index, d, cost)
        }
        return best?.let { it.first to it.second }
    }

    /** Timetable time at a fractional stop index, in epoch ms. */
    fun scheduledAt(index: Float, scheduledMs: List<Long>): Long {
        if (scheduledMs.isEmpty()) return 0L
        val i = index.toInt().coerceIn(0, scheduledMs.lastIndex)
        if (i == scheduledMs.lastIndex) return scheduledMs.last()
        val frac = (index - i).coerceIn(0f, 1f)
        return scheduledMs[i] + ((scheduledMs[i + 1] - scheduledMs[i]) * frac).toLong()
    }

    /**
     * The timetable moment a bus seen at [index] at [nowMs] is measured against. Timetable
     * times are departures: behind them the bus is late, but it is early only once it has
     * left a stop before that stop's time (a bus waiting at a stop early just waits).
     */
    fun referenceAt(index: Float, scheduledMs: List<Long>, nowMs: Long): Long {
        val at = scheduledAt(index, scheduledMs)
        if (nowMs >= at || scheduledMs.isEmpty()) return at
        // A little past a stop still counts as at it (creeping out, GPS noise).
        val left = floor(index - 0.1f).toInt()
        if (left < 0) return nowMs
        return maxOf(nowMs, scheduledMs[left.coerceAtMost(scheduledMs.lastIndex)])
    }

    /** Delay in seconds of a bus seen at [index] at [nowMs]. */
    fun delayAt(index: Float, scheduledMs: List<Long>, nowMs: Long): Int =
        ((nowMs - referenceAt(index, scheduledMs, nowMs)) / 1000L).toInt()
}

/**
 * When the bus left or drove past each stop, from the rider's fixes (every few seconds, also
 * while it stands). Timetable times are departures, so that moment is what the delay is
 * measured at. Stop positions are only roughly where buses really stop, so crossing a stop
 * decides nothing yet:
 * - it stops anywhere within the stop's area (before or after the point): it stopped there,
 *   and left when it moved on (between the last two fixes);
 * - it leaves the area without having stopped: it drove past, at the moment it crossed the point.
 */
/** The bus left [stop] at [atMs]; [stopped]: it stood there first (else it drove past). */
data class StopPass(val stop: Int, val atMs: Long, val stopped: Boolean)

class StopPassDetector {
    private var lastIndex = -1f
    private var lastAt = 0L
    /** Standing at this stop: it leaves when it moves again. */
    private var waitingAt = -1
    /** Crossed this stop while moving; stopped or passed is decided once it leaves the area. */
    private var crossed: StopPass? = null
    /** Stood in the area of the stop ahead, before reaching its point. */
    private var stoppedBefore = false

    /**
     * A fix at fractional stop [index]; returns when and how the bus left a stop once that is
     * certain. [atStop]: the fix is within the stop's area (a few tens of metres of it).
     */
    fun onFix(index: Float, moving: Boolean, atMs: Long, atStop: Boolean = false): StopPass? {
        var result: StopPass? = null
        // [atStop] is about the nearest stop; stops close together have overlapping areas.
        val nearest = kotlin.math.round(index).toInt()
        crossed?.let { pending ->
            val inArea = atStop && nearest == pending.stop
            when {
                // Stopped just past the point: it stopped there after all.
                !moving && inArea -> { waitingAt = pending.stop; crossed = null }
                // Out of the stop's area without stopping: it drove past.
                !inArea -> { result = pending; crossed = null }
            }
        }
        if (result == null && waitingAt >= 0 && moving) {
            result = StopPass(waitingAt, (lastAt + atMs) / 2, stopped = true)
            waitingAt = -1
        }
        if (lastIndex >= 0f) {
            val stop = floor(index).toInt()
            if (stop > floor(lastIndex).toInt() && index > lastIndex) {
                val at = lastAt + kotlin.math.round((stop - lastIndex).toDouble() / (index - lastIndex) * (atMs - lastAt)).toLong()
                when {
                    // Stood just before the point, then drove on through it.
                    stoppedBefore && moving -> result = result ?: StopPass(stop, at, stopped = true)
                    // Already out of the stop's area on the far side: it drove past.
                    moving && !(atStop && nearest == stop) -> result = result ?: StopPass(stop, at, stopped = false)
                    moving -> crossed = StopPass(stop, at, stopped = false)
                    else -> waitingAt = stop
                }
            }
        }
        // Only the stop ahead counts (standing just after a stop is [waitingAt]).
        val atStopAhead = atStop && nearest > floor(index).toInt()
        stoppedBefore = (!moving && atStopAhead) || (stoppedBefore && atStopAhead)
        if (index >= lastIndex) {
            lastIndex = index
            lastAt = atMs
        } else {
            lastAt = atMs // GPS jitter backwards: keep the furthest point, but the latest time
        }
        return result
    }
}

