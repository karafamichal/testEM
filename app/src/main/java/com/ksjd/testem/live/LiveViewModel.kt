package com.ksjd.testem.live

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ksjd.testem.CredentialsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
    /** Next departures from the first favourite stop, shown on the ticket tab. */
    val preview: BoardState? = null
)

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

    fun findNearby() {
        val context = getApplication<Application>()
        if (!hasLocationPermission(context)) {
            onLocationPermissionDenied()
            return
        }
        _state.update { it.copy(locationStatus = LocationStatus.Locating) }
        viewModelScope.launch {
            val location = currentLocation(context)
            if (location == null) {
                _state.update { it.copy(locationStatus = LocationStatus.Unavailable) }
                return@launch
            }
            runCatching { repo.nearbyStops(location.latitude, location.longitude) }
                .onSuccess { nearby -> _state.update { it.copy(nearby = nearby, locationStatus = LocationStatus.Ready) } }
                .onFailure { _state.update { it.copy(locationStatus = LocationStatus.Unavailable, stopsFailed = true) } }
        }
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60 * 1000 }
        if (recent != null) return recent
        val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location -> if (cont.isActive) cont.resume(location) }
            }
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
                val result = runCatching { repo.getDepartures(stop) }
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
        val stop = _state.value.favourites.firstOrNull()
        if (stop == null) {
            _state.update { it.copy(preview = null) }
            return
        }
        viewModelScope.launch {
            val result = runCatching { repo.getDepartures(stop) }
            _state.update { state ->
                state.copy(
                    preview = result.fold(
                        onSuccess = { BoardState(stop, it.take(3), isLoading = false, updatedAtMs = System.currentTimeMillis()) },
                        onFailure = { BoardState(stop, state.preview?.departures.orEmpty(), isLoading = false, hasError = true) }
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ trip

    fun openTrip(departure: LiveDeparture) {
        val ref = TripRef(
            line = departure.line,
            lineId = departure.lineId,
            routeNumber = departure.routeNumber,
            tripNumber = departure.tripNumber,
            destination = departure.destination
        )
        val boarding = _state.value.board?.stop?.platforms?.map { it.id }.orEmpty()
        _state.update { it.copy(trip = TripSheetState(ref, boardingPlatformIds = boarding)) }
        tripJob?.cancel()
        tripJob = viewModelScope.launch {
            val result = runCatching { repo.getTrip(ref) }
            _state.update { state ->
                val trip = state.trip?.takeIf { it.ref == ref } ?: return@update state
                state.copy(
                    trip = result.fold(
                        onSuccess = { trip.copy(detail = it, isLoading = false) },
                        onFailure = { trip.copy(isLoading = false, hasError = true) }
                    )
                )
            }
        }
    }

    fun closeTrip() {
        tripJob?.cancel()
        _state.update { it.copy(trip = null) }
    }

    companion object {
        private const val BOARD_REFRESH_MS = 15_000L
    }
}
