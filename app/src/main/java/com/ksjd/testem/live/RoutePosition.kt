package com.ksjd.testem.live

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a GPS point lies along a trip's stops. Pure logic, no Android types. */
object RoutePosition {
    /** A GPS point further than this from every stop-to-stop line is not on the route. */
    const val BUS_GPS_MAX_M = 300.0
    /** Tighter for a rider's phone: they must be on the bus, not walking beside the road. */
    const val RIDER_GPS_MAX_M = 80.0
    /** Positions older than this are ignored (sadzv sometimes keeps positions for months). */
    const val FRESH_MS = 2 * 60_000L

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
            if (d > maxMeters) continue
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

    /** Delay in seconds of a bus seen at [index] at [nowMs]. */
    fun delayAt(index: Float, scheduledMs: List<Long>, nowMs: Long): Int =
        ((nowMs - scheduledAt(index, scheduledMs)) / 1000L).toInt()
}
