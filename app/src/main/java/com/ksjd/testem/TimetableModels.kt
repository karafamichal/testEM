package com.ksjd.testem

/** Planner scope covering all of Slovakia, city buses included. */
const val PLANNER_ALL = "vlakbusmhd"

data class CpStopSuggestion(
    val selectedText: String,
    val value: String,
    val value2: String,
    val coorX: String? = null,
    val coorY: String? = null,
    val description: String = ""
) {
    fun toHiddenFieldValue(): String = "$selectedText%$value%$value2"
    fun toPositionFieldValue(): String = if (!coorX.isNullOrBlank() && !coorY.isNullOrBlank()) {
        "$coorX%$coorY"
    } else {
        ""
    }
}

data class TimetableSegment(
    val line: String,
    val operatorName: String,
    val departureTime: String,
    val departureStop: String,
    val arrivalTime: String,
    val arrivalStop: String,
    val departurePlatform: String = "",
    val arrivalPlatform: String = "",
    /** Trip link with every stop of this bus (TimetableService.tripUrl). */
    val routeUrl: String = "",
    /** ISO date of [departureTime]. */
    val serviceDate: String = ""
)

data class TimetableConnection(
    val id: String,
    val departureTime: String,
    val arrivalTime: String,
    val totalDuration: String,
    val segments: List<TimetableSegment>
) {
    val isDirect: Boolean
        get() = segments.size <= 1

    /** Sorts connections by date and time. */
    val departureSortKey: String
        get() = segments.firstOrNull()?.let { "${it.serviceDate} ${it.departureTime.padStart(5, '0')}" }.orEmpty()
}

data class TimetablePagingCursor(
    val citySlug: String,
    val routePrefix: String,
    val fromText: String,
    val toText: String,
    val handle: String,
    val searchDate: String,
    val listedIds: List<String>,
    val allowNext: Boolean = true
)

data class TimetableSearchResult(
    val connections: List<TimetableConnection>,
    val pagingCursor: TimetablePagingCursor?
)

data class TimetableState(
    val isLoading: Boolean = false,
    /** Planner scope; one timetable for all buses now, kept for saved routes. */
    val citySlug: String = PLANNER_ALL,
    val fromInput: String = "",
    val toInput: String = "",
    val timeInput: String = "",
    /** ISO date to search on, "" = today. */
    val dateInput: String = "",
    val directOnly: Boolean = false,
    /** EM-0053: the time is when to be there, not when to leave. */
    val arriveBy: Boolean = false,
    val fromSuggestions: List<CpStopSuggestion> = emptyList(),
    val toSuggestions: List<CpStopSuggestion> = emptyList(),
    val selectedFromSuggestion: CpStopSuggestion? = null,
    val selectedToSuggestion: CpStopSuggestion? = null,
    val isLoadingFromSuggestions: Boolean = false,
    val isLoadingToSuggestions: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val pagingCursor: TimetablePagingCursor? = null,
    val connections: List<TimetableConnection> = emptyList(),
    val errorMessage: String = "",
    val savedRoutes: List<SavedRoute> = emptyList(),
    val recentRoutes: List<SavedRoute> = emptyList(),
    /** Starred connections, shown on the home tab. */
    val savedJourneys: List<SavedJourney> = emptyList(),
    val rides: List<RecurringRide> = emptyList()
)

/** A from/to pair the user saved or searched recently. */
data class SavedRoute(
    val citySlug: String,
    val fromText: String,
    val toText: String,
    val from: CpStopSuggestion? = null,
    val to: CpStopSuggestion? = null
) {
    fun sameAs(other: SavedRoute): Boolean =
        fromText.equals(other.fromText, ignoreCase = true) && toText.equals(other.toText, ignoreCase = true)
}
