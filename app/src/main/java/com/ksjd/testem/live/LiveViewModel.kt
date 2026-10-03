package com.ksjd.testem.live

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.TimetableSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BoardState(
    val stop: LiveStop,
    val departures: List<LiveDeparture> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val updatedAtMs: Long = 0L,
    /** Departure to point out, e.g. when coming from a planner result. */
    val highlight: BoardHighlight? = null
)

data class BoardHighlight(val line: String, val time: String)

data class TripSheetState(
    val ref: TripRef,
    /** Platforms of the stop the board was opened for, i.e. where the user boards. */
    val boardingPlatformIds: List<Int> = emptyList(),
    /** Where the user gets off, when known (planner trips). */
    val alightOrder: Int? = null,
    val detail: TripDetail? = null,
    val isLoading: Boolean = true,
    val hasError: Boolean = false
)

enum class LocationStatus { Idle, Locating, Denied, Unavailable, Ready }

data class LiveState(
    val query: String = "",
    val results: List<LiveStop> = emptyList(),
    val favourites: List<LiveStop> = emptyList(),
    val favouriteIds: List<Int> = emptyList(),
    val nearby: List<Pair<LiveStop, Int>> = emptyList(),
    val locationStatus: LocationStatus = LocationStatus.Idle,
    val stopsFailed: Boolean = false,
    val board: BoardState? = null,
    val trip: TripSheetState? = null,
    /** Next departures from the first few favourite stops, shown on the home tab. */
    val previews: List<BoardState> = emptyList()
)

/**
 * A planner bus to follow. A SAD Zvolen bus from the live data is followed live, from the stop
 * where the user gets on; any other bus by its timetable trip, which knows the user's stops itself.
 */
fun plannerTrip(segment: TimetableSegment, line: String): TrackedTrip? {
    val live = com.ksjd.testem.TimetableService.parseLiveUrl(segment.routeUrl)
        ?: return scheduledTripRef(segment, line)?.let { TrackedTrip(it, emptyList()) }
    // sadzv only knows today's run: a city bus on another day comes from the timetable, with that day's times.
    if (live.timetable.isNotEmpty() && segment.serviceDate != java.time.LocalDate.now(com.ksjd.testem.SK_ZONE).toString()) {
        return scheduledTripRef(segment.copy(routeUrl = live.timetable), line)?.let { TrackedTrip(it, emptyList()) }
    }
    val ref = TripRef(
        line = live.line,
        lineId = live.lineId,
        routeNumber = live.routeNumber,
        tripNumber = live.tripNumber,
        destination = segment.arrivalStop,
        serviceDate = segment.serviceDate,
        fromStopId = live.stopId,
        plannedSecondOfDay = live.plannedSecondOfDay
    )
    return TrackedTrip(ref, listOfNotNull(live.boardingPlatformId.takeIf { it != 0 }), live.alightOrder.takeIf { it != 0 })
}

/** The open trip as a bus of the user's own journey (see DraftLeg). */
fun draftLeg(sheet: TripSheetState, detail: TripDetail): DraftLeg {
    val trip = TrackedTrip(sheet.ref, sheet.boardingPlatformIds, sheet.alightOrder)
    val stops = detail.stops
    if (stops.size < 2) return DraftLeg(trip, null, null, null)
    val ref = sheet.ref
    val timetable = ref.isScheduleOnly || detail.fromTimetable
    val board = (if (timetable) stops.indexOfFirst { it.order == detail.boardingOrder }
        else stops.indexOfFirst { it.platformId in sheet.boardingPlatformIds }).takeIf { it >= 0 } ?: 0
    val alight = stops.indexOfFirst { it.order == (sheet.alightOrder ?: detail.alightOrder) }.takeIf { it > board } ?: stops.lastIndex
    val (b, a) = stops[board] to stops[alight]
    fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(com.ksjd.testem.SK_ZONE)
    val url = when {
        ref.isScheduleOnly -> com.ksjd.testem.TimetableService.parseTripUrl(ref.scheduleUrl)
            ?.let { (id, _, _) -> com.ksjd.testem.TimetableService.tripUrl(id, board, alight) }.orEmpty()
        ref.lineId != 0L -> com.ksjd.testem.TimetableService.liveUrl(com.ksjd.testem.TimetableService.Companion.LiveLink(
            ref.line, ref.lineId, ref.routeNumber, ref.tripNumber,
            if (timetable) sheet.boardingPlatformIds.firstOrNull() ?: 0 else b.platformId, a.order, ref.fromStopId,
            local(b.scheduledMs).toLocalTime().toSecondOfDay()))
        else -> ""
    }
    fun clock(ms: Long) = local(ms).let { "%d:%02d".format(it.hour, it.minute) }
    val segment = TimetableSegment(
        line = "Bus ${ref.line}", operatorName = "",
        departureTime = clock(b.scheduledMs), departureStop = b.name,
        arrivalTime = clock(a.scheduledMs), arrivalStop = a.name,
        departurePlatform = b.platform, arrivalPlatform = a.platform,
        routeUrl = url, serviceDate = local(b.scheduledMs).toLocalDate().toString()
    )
    return DraftLeg(trip, segment, b.lat?.let { it to b.lon!! }, a.lat?.let { it to a.lon!! })
}

/** A planner bus as a timetable trip: stops, times and the user's stops come from its trip link. */
fun scheduledTripRef(segment: TimetableSegment, line: String): TripRef? {
    if (segment.routeUrl.isBlank() || segment.routeUrl.startsWith("sadzv:")) return null
    return TripRef(
        line = line,
        lineId = 0L,
        routeNumber = "",
        tripNumber = 0,
        destination = segment.arrivalStop.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
        scheduleUrl = segment.routeUrl,
        serviceDate = segment.serviceDate
    )
}

class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = LiveRepository(application)
    private val prefs = CredentialsManager(application)

    private val _state = MutableStateFlow(LiveState(favouriteIds = prefs.getFavouriteStopIds()))
    val state: StateFlow<LiveState> = _state

    private var searchJob: Job? = null
    private var boardJob: Job? = null
    private var tripJob: Job? = null

    init {
        viewModelScope.launch { loadFavourites() }
    }

    private suspend fun loadFavourites() {
        val result = runCatching { repo.getStops() }
        result.onSuccess { stops ->
            val ids = _state.value.favouriteIds
            _state.update {
                it.copy(favourites = ids.mapNotNull { id -> stops.firstOrNull { s -> s.id == id } }, stopsFailed = false)
            }
        }.onFailure {
            _state.update { it.copy(stopsFailed = true) }
        }
    }

    fun retryStops() = viewModelScope.launch {
        loadFavourites()
        if (_state.value.query.isNotBlank()) search(_state.value.query)
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(150)
            runCatching { repo.searchStops(query) }
                .onSuccess { results -> _state.update { it.copy(results = results, stopsFailed = false) } }
                .onFailure { _state.update { it.copy(stopsFailed = true) } }
        }
    }

    fun toggleFavourite(stop: LiveStop) {
        val ids = _state.value.favouriteIds
        val updated = if (stop.id in ids) ids - stop.id else ids + stop.id
        prefs.saveFavouriteStopIds(updated)
        _state.update { state ->
            val favourites = if (stop.id in ids) {
                state.favourites.filterNot { it.id == stop.id }
            } else {
                state.favourites + stop
            }
            state.copy(favouriteIds = updated, favourites = favourites)
        }
    }

    // ---------------------------------------------------------------- nearby

    fun onLocationPermissionDenied() = _state.update { it.copy(locationStatus = LocationStatus.Denied) }

    /** [fresh]: the Refresh button wants a new fix, not the cached one from minutes ago. */
    fun findNearby(fresh: Boolean = false) {
        val context = getApplication<Application>()
        if (!Locations.hasPermission(context)) {
            onLocationPermissionDenied()
            return
        }
        _state.update { it.copy(locationStatus = LocationStatus.Locating) }
        viewModelScope.launch {
            val location = Locations.current(context, maxAgeMs = if (fresh) 15_000L else 10 * 60 * 1000L, fallbackToLastKnown = true)
            if (location == null) {
                _state.update { it.copy(locationStatus = LocationStatus.Unavailable) }
                return@launch
            }
            runCatching { repo.nearbyStops(location.latitude, location.longitude) }
                .onSuccess { nearby -> _state.update { it.copy(nearby = nearby, locationStatus = LocationStatus.Ready) } }
                .onFailure { _state.update { it.copy(locationStatus = LocationStatus.Unavailable, stopsFailed = true) } }
        }
    }

    // ----------------------------------------------------------------- board

    fun openStop(stop: LiveStop, highlight: BoardHighlight? = null) {
        _state.update { it.copy(board = BoardState(stop = stop, highlight = highlight)) }
        startBoardRefresh()
    }

    /** Opens the board for a stop named by another source (planner results). */
    fun openStopByName(name: String, highlight: BoardHighlight?, onNotFound: () -> Unit) {
        viewModelScope.launch {
            val stop = runCatching { repo.matchStopByName(name) }.getOrNull()
            if (stop == null) onNotFound() else openStop(stop, highlight)
        }
    }

    fun closeStop() {
        boardJob?.cancel()
        _state.update { it.copy(board = null) }
    }

    fun refreshBoard() = startBoardRefresh()

    /** Called when the app goes to the background. */
    fun pause() {
        boardJob?.cancel()
    }

    fun resume() {
        if (_state.value.board != null) startBoardRefresh()
    }

    private fun startBoardRefresh() {
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            while (isActive) {
                val stop = _state.value.board?.stop ?: return@launch
                val result = runCatching { repo.getBoard(stop) }
                _state.update { state ->
                    val board = state.board?.takeIf { it.stop.id == stop.id } ?: return@update state
                    state.copy(
                        board = result.fold(
                            onSuccess = { board.copy(departures = it, isLoading = false, hasError = false, updatedAtMs = System.currentTimeMillis()) },
                            onFailure = { board.copy(isLoading = false, hasError = true) }
                        )
                    )
                }
                delay(BOARD_REFRESH_MS)
            }
        }
    }

    // --------------------------------------------------------------- preview

    fun refreshPreview() {
        val stops = _state.value.favourites.take(PREVIEW_STOPS)
        if (stops.isEmpty()) {
            _state.update { it.copy(previews = emptyList()) }
            return
        }
        viewModelScope.launch {
            val boards = stops.map { stop ->
                val old = _state.value.previews.firstOrNull { it.stop.id == stop.id }
                runCatching { repo.getBoard(stop) }.fold(
                    onSuccess = { BoardState(stop, it.take(3), isLoading = false, updatedAtMs = System.currentTimeMillis()) },
                    onFailure = { BoardState(stop, old?.departures.orEmpty(), isLoading = false, hasError = true) }
                )
            }
            _state.update { it.copy(previews = boards) }
            com.ksjd.testem.widget.PlanRideWidget.refresh(getApplication())
        }
    }

    // ------------------------------------------------------------------ trip

    fun openTrip(departure: LiveDeparture) {
        val ref = TripRef(
            line = departure.line,
            lineId = departure.lineId,
            routeNumber = departure.routeNumber,
            tripNumber = departure.tripNumber,
            destination = departure.destination,
            scheduleUrl = departure.scheduleUrl,
            serviceDate = departure.serviceDate,
            fromStopId = _state.value.board?.stop?.id ?: 0,
            plannedSecondOfDay = departure.plannedSecondOfDay
        )
        val boarding = _state.value.board?.stop?.platforms?.map { it.id }.orEmpty()
        loadTrip(TripSheetState(ref, boardingPlatformIds = boarding))
    }

    /** Adds the open bus to the journey being built; its stops get positions so the journey can be starred or repeated. */
    fun addToDraft(sheet: TripSheetState) {
        val detail = sheet.detail ?: return
        viewModelScope.launch {
            val placed = runCatching { repo.withCoordinates(sheet.ref, detail) }.getOrDefault(detail)
            TripTracking.addToDraft(draftLeg(sheet, placed))
        }
    }

    /** A bus from a planner result: no live data, so its stops and times come from the timetable. */
    fun openScheduledTrip(segment: TimetableSegment, line: String) {
        val trip = plannerTrip(segment, line) ?: return
        loadTrip(TripSheetState(trip.ref, boardingPlatformIds = trip.boardingPlatformIds, alightOrder = trip.alightOrder))
    }

    /** The bus being followed, with the user's stops, e.g. after tapping its notification. */
    fun openFollowed(trip: TrackedTrip) {
        loadTrip(TripSheetState(trip.ref, boardingPlatformIds = trip.boardingPlatformIds, alightOrder = trip.alightOrder))
    }

    /** Where the user gets off the bus in the open sheet (to add it to a journey), null = clear. */
    fun setTripAlight(order: Int?) {
        _state.update { it.copy(trip = it.trip?.copy(alightOrder = order)) }
    }

    private fun loadTrip(sheet: TripSheetState) {
        _state.update { it.copy(trip = sheet) }
        tripJob?.cancel()
        tripJob = viewModelScope.launch {
            val result = runCatching { repo.getTrip(sheet.ref) }
            _state.update { state ->
                val trip = state.trip?.takeIf { it.ref == sheet.ref } ?: return@update state
                state.copy(
                    trip = result.fold(
                        onSuccess = { detail ->
                            trip.copy(
                                detail = detail,
                                isLoading = false,
                                // Timetable trips know the user's stops; stop ids equal their order there.
                                boardingPlatformIds = detail.boardingOrder?.let { listOf(it) } ?: trip.boardingPlatformIds,
                                alightOrder = if (detail.fromTimetable) trip.alightOrder else detail.alightOrder ?: trip.alightOrder
                            )
                        },
                        onFailure = { trip.copy(isLoading = false, hasError = true) }
                    )
                )
            }
            // Keep the open sheet fresh: riders' reports and the bus's position change all the time.
            while (isActive) {
                delay(TRIP_REFRESH_MS)
                val detail = runCatching { repo.getTrip(sheet.ref) }.getOrNull() ?: continue
                _state.update { state ->
                    val open = state.trip?.takeIf { it.ref == sheet.ref && it.detail != null } ?: return@update state
                    state.copy(trip = open.copy(detail = detail))
                }
            }
        }
    }

    fun closeTrip() {
        tripJob?.cancel()
        _state.update { it.copy(trip = null) }
    }

    companion object {
        private const val BOARD_REFRESH_MS = 15_000L
        private const val TRIP_REFRESH_MS = 15_000L
        private const val PREVIEW_STOPS = 3
    }
}
