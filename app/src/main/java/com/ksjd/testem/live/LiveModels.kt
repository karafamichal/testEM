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
    val delaySeconds: Int?
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
    val platform: String = ""
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
    /** Pooled rider reports for timetable-only trips (when the user opted in). */
    val community: com.ksjd.testem.hub.CommunityDelay? = null
) {
    /**
     * Same bus, same key on every phone and on the web: line, first stop and its
     * departure time. Only timetable trips have one.
     */
    val communityKey: String?
        get() = stops.firstOrNull()?.let { "$line|${it.name}|${it.scheduledMs}" }
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
    val serviceDate: String = ""
) {
    val isScheduleOnly: Boolean get() = scheduleUrl.isNotBlank()
}
