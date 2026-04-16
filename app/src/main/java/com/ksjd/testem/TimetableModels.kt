package com.ksjd.testem

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
    val arrivalStop: String
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
    val citySlug: String = "slovensko",
    val fromInput: String = "",
    val toInput: String = "",
    val timeInput: String = "",
    val directOnly: Boolean = false,
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
    val errorMessage: String = ""
)
