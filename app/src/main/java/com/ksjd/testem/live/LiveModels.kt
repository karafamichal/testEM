package com.ksjd.testem.live

/** One boarding point (a stop usually has one platform per direction). */
data class LivePlatform(
    val id: Int,
    val name: String,
    val number: String,
    val lat: Double,
    val lon: Double,
    val stopId: Int
)

/** A named stop grouping all of its platforms. */
data class LiveStop(
    val id: Int,
    val name: String,
    val platforms: List<LivePlatform>,
    val lat: Double,
    val lon: Double,
    /** Diacritic-free lowercase name used for searching. */
    val searchKey: String
)

data class LiveDeparture(
    val line: String,
    val lineId: Long,
    val destination: String,
    val platformNumber: String,
    val routeNumber: String,
    val tripNumber: Int,
    /** Scheduled departure, seconds after local midnight. */
    val plannedSecondOfDay: Int,
    /** Seconds until departure as predicted by the operator (includes delay when [isRealtime]). */
    val secondsUntil: Int,
    /** True when a tracked vehicle is paired with this departure. */
    val isRealtime: Boolean,
    val isCancelled: Boolean,
    /** Delay in seconds; null when the departure is not tracked live. */
    val delaySeconds: Int?,
    val fetchedAtMs: Long
) {
    val key: String get() = "$lineId-$tripNumber-$plannedSecondOfDay"
}

data class LiveVehicle(
    val lat: Double,
    val lon: Double,
    val line: String,
    val lineId: Long,
    val routeNumber: String,
    val tripNumber: Int,
    val delaySeconds: Int?,
    /** When the bus last reported (sadzv sends Slovak local time marked as UTC). */
    val reportedAtMs: Long = 0L
)

data class TripStop(
    val name: String,
    val platformId: Int,
    val order: Int,
    /** Scheduled departure in epoch ms (already converted from local wall-clock). */
    val scheduledMs: Long,
    /** Actual departure in epoch ms, null if the bus has not left this stop yet. */
    val actualMs: Long?,
    /** Platform or track at this stop (timetable trips from cp.sk), "" when unknown. */
    val platform: String = "",
    /** Stop position, filled in only when a GPS position has to be placed on the route. */
    val lat: Double? = null,
    val lon: Double? = null
) {
    val isPassed: Boolean get() = actualMs != null
}

data class TripDetail(
    val line: String,
    val destination: String,
    val stops: List<TripStop>,
    /** Latest known delay in seconds (from the vehicle feed or the last passed stop). */
    val delaySeconds: Int?,
    /** For timetable-only trips: the stops where the user's part of the journey starts and ends. */
    val boardingOrder: Int? = null,
    val alightOrder: Int? = null,
    /** sadzv had no stop list, so stops and times come from cp.sk (live delay still from sadzv). */
    val fromTimetable: Boolean = false,
    /** Pooled rider reports (when the user opted in). */
    val community: com.ksjd.testem.hub.CommunityDelay? = null,
    /** How late this trip usually is, from riders' reports on earlier days. */
    val history: com.ksjd.testem.hub.TripHistory? = null,
    /** Same bus, same key on every phone and on the web (see LiveRepository.communityKey). */
    val communityKey: String? = null,
    /** Where the current position comes from, best first: rider GPS, bus GPS, riders' taps, delay, timetable. */
    val positionSource: PositionSource = PositionSource.Timetable,
    /** The bus's own GPS position when it is fresh (to place it on the route). */
    val busPosition: Pair<Double, Double>? = null
)

enum class PositionSource {
    /** History: the timetable shifted by how late this trip usually is (when the user chose that). */
    Timetable, History, BusDelay, Riders, BusGps, RiderGps;

    /** Someone knows exactly where the bus is: no need to ask riders to tap. */
    val isLive: Boolean get() = this == BusGps || this == RiderGps
}

/** Minimal description of a trip, enough to query its stop list. */
data class TripRef(
    val line: String,
    val lineId: Long,
    val routeNumber: String,
    val tripNumber: Int,
    val destination: String,
    /** cp.sk route page for buses without live data; times then come from the timetable only. */
    val scheduleUrl: String = "",
    /** ISO date of the departure from the boarding stop, needed to place timetable times. */
    val serviceDate: String = "",
    /** sadzv stop where the user boards and the planned departure (seconds after midnight):
     *  used to find the trip on cp.sk when sadzv has no stop list for it. */
    val fromStopId: Int = 0,
    val plannedSecondOfDay: Int = -1
) {
    val isScheduleOnly: Boolean get() = scheduleUrl.isNotBlank()
}
