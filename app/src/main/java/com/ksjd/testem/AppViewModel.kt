package com.ksjd.testem

import android.app.Application
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ksjd.testem.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TicketState(
    val phase: TicketPhase = TicketPhase.Idle,
    val qrBitmap: Bitmap? = null,
    val tokenHex: String = "",
    val tokenBase64: String = "",
    /** When the currently shown code was received (0 = never). */
    val lastUpdateTime: Long = 0,
    /** When the server last confirmed the code is current. */
    val lastConfirmedTime: Long = 0,
    val userName: String = "",
    val cards: List<AccountDetails> = emptyList(),
    val selectedSnr: String = "",
    val isRefreshingAccount: Boolean = false,
    val historyState: CardHistoryState = CardHistoryState()
) {
    val selectedCard: AccountDetails?
        get() = cards.firstOrNull { it.snr == selectedSnr } ?: cards.firstOrNull()
}

data class AppState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val loginError: String = "",
    val email: String = "",
    val password: String = "",
    val themePresets: List<ThemePreset> = emptyList(),
    val selectedThemeId: String = "",
    val amoledEnabled: Boolean = false,
    val isPinSet: Boolean = false,
    val isAppUnlocked: Boolean = false,
    val biometricEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 0,
    val lowCreditWarningThreshold: Double = 1.0,
    val lowCreditAlerts: Boolean = false,
    val expiryAlerts: Boolean = false,
    val languageCode: String = "sk"
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = CredentialsManager(application)
    private val cpTimetableService = CpTimetableService()

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState

    private val _ticketState = MutableStateFlow(TicketState())
    val ticketState: StateFlow<TicketState> = _ticketState

    private val _timetableState = MutableStateFlow(TimetableState())
    val timetableState: StateFlow<TimetableState> = _timetableState

    private var session: TicketSession? = null
    private var pollingJob: Job? = null
    private var isInForeground = true
    private var lastBackgroundTimeMs = 0L
    private var fromSuggestionJob: Job? = null
    private var toSuggestionJob: Job? = null

    init {
        val presets = prefs.getThemePresets(DefaultThemePresets.all)
        val pinSet = prefs.isPinSet()
        _appState.value = AppState(
            email = prefs.getEmail(),
            password = prefs.getPassword(),
            themePresets = presets,
            selectedThemeId = prefs.getSelectedThemeId(presets.first().id),
            amoledEnabled = prefs.getAmoledEnabled(),
            isPinSet = pinSet,
            isAppUnlocked = !pinSet,
            biometricEnabled = prefs.getBiometricEnabled(),
            lockTimeoutSeconds = prefs.getLockTimeoutSeconds(),
            lowCreditWarningThreshold = prefs.getLowCreditWarningThreshold(),
            lowCreditAlerts = prefs.getLowCreditAlertsEnabled(),
            expiryAlerts = prefs.getExpiryAlertsEnabled(),
            languageCode = prefs.getLanguageCode()
        )
        prefs.getLastAccount()?.let { cached ->
            _ticketState.update {
                it.copy(userName = cached.userName, cards = cached.cards, selectedSnr = prefs.getSelectedCardSnr())
            }
        }
        _timetableState.update {
            it.copy(savedRoutes = prefs.getSavedRoutes(), recentRoutes = prefs.getRecentRoutes())
        }
    }

    // ================================================================ login

    fun hasSavedCredentials(): Boolean = _appState.value.let { it.email.isNotBlank() && it.password.isNotBlank() }

    fun loginWithSavedCredentials() {
        val state = _appState.value
        if (state.isLoading || state.isLoggedIn || !hasSavedCredentials()) return
        login(state.email, state.password)
    }

    fun login(email: String, password: String) {
        val emailTrimmed = email.trim()
        if (emailTrimmed.isEmpty() || password.isEmpty()) {
            _appState.update { it.copy(loginError = app.getString(R.string.login_error_fill_fields)) }
            return
        }
        _appState.update { it.copy(isLoading = true, loginError = "") }
        val newSession = TicketSession(QRDaemonConfig.BASE_URL, emailTrimmed, password).apply {
            activeSnr = prefs.getSelectedCardSnr()
        }
        viewModelScope.launch {
            val result = runCatching { newSession.fetchAccount() }
            result.onSuccess { snapshot ->
                session = newSession
                prefs.saveCredentials(emailTrimmed, password)
                _appState.update {
                    it.copy(isLoggedIn = true, isLoading = false, email = emailTrimmed, password = password)
                }
                applyAccount(snapshot)
                startPolling()
            }.onFailure { error ->
                val message = when (error) {
                    is LoginRejectedException -> app.getString(R.string.login_error_rejected)
                    else -> app.getString(R.string.login_error_network)
                }
                _appState.update { it.copy(isLoading = false, loginError = message) }
            }
        }
    }

    /** The server stopped accepting the saved password: go back to login with the email kept. */
    fun returnToLogin() {
        stopPolling()
        session = null
        val email = _appState.value.email
        prefs.saveCredentials(email, "")
        _appState.update { it.copy(isLoggedIn = false, isLoading = false, password = "") }
        _ticketState.value = TicketState()
    }

    fun logout() {
        stopPolling()
        session = null
        prefs.clearCredentials()
        Reminders.cancel(app)
        _appState.update {
            it.copy(isLoggedIn = false, isLoading = false, loginError = "", email = "", password = "")
        }
        _ticketState.value = TicketState()
    }

    // ============================================================== polling

    fun startPolling() {
        val current = session ?: return
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            current.pollTokens(object : TicketSession.Listener {
                override fun onPhase(phase: TicketPhase) {
                    _ticketState.update { state ->
                        state.copy(
                            phase = phase,
                            lastConfirmedTime = if (phase == TicketPhase.Active) System.currentTimeMillis() else state.lastConfirmedTime
                        )
                    }
                    if (phase == TicketPhase.SignedOut) {
                        _appState.update { it.copy(loginError = app.getString(R.string.login_error_expired)) }
                    }
                }

                override fun onToken(base64: String, hex: String) {
                    viewModelScope.launch {
                        val bitmap = withContext(Dispatchers.Default) {
                            QRCodeGenerator.generateQRCode(base64, QRDaemonConfig.QR_BITMAP_SIZE, QRDaemonConfig.QR_BITMAP_SIZE)
                        }
                        val now = System.currentTimeMillis()
                        _ticketState.update {
                            it.copy(qrBitmap = bitmap, tokenBase64 = base64, tokenHex = hex, lastUpdateTime = now, lastConfirmedTime = now)
                        }
                    }
                }

                override fun onAccount(snapshot: AccountSnapshot) {
                    viewModelScope.launch { applyAccount(snapshot) }
                }
            })
        }
    }

    fun stopPolling(phase: TicketPhase = TicketPhase.Paused) {
        pollingJob?.cancel()
        pollingJob = null
        if (session != null) _ticketState.update { it.copy(phase = phase) }
    }

    val isPolling: Boolean get() = pollingJob?.isActive == true

    // ============================================================== account

    private fun applyAccount(snapshot: AccountSnapshot) {
        prefs.saveLastAccount(snapshot)
        val snr = session?.activeSnr?.takeIf { it.isNotBlank() }
            ?: snapshot.cards.firstOrNull()?.snr.orEmpty()
        if (snr.isNotBlank()) prefs.saveSelectedCardSnr(snr)
        _ticketState.update {
            it.copy(userName = snapshot.userName.ifBlank { it.userName }, cards = snapshot.cards, selectedSnr = snr)
        }
        Reminders.evaluate(app, snapshot)
    }

    fun refreshAccount() {
        val current = session ?: return
        if (_ticketState.value.isRefreshingAccount) return
        viewModelScope.launch {
            _ticketState.update { it.copy(isRefreshingAccount = true) }
            runCatching { current.fetchAccount() }.onSuccess { applyAccount(it) }
            _ticketState.update { it.copy(isRefreshingAccount = false) }
        }
    }

    fun selectCard(snr: String) {
        if (snr == _ticketState.value.selectedSnr) return
        session?.activeSnr = snr
        prefs.saveSelectedCardSnr(snr)
        _ticketState.update {
            it.copy(
                selectedSnr = snr,
                qrBitmap = null,
                tokenBase64 = "",
                tokenHex = "",
                lastUpdateTime = 0,
                historyState = CardHistoryState()
            )
        }
        // Restart polling so the new card's code is fetched right away.
        if (pollingJob?.isActive == true) {
            stopPolling(TicketPhase.Connecting)
            startPolling()
        }
    }

    fun loadCardHistory(limit: Int = 100) {
        val current = session ?: return
        if (_ticketState.value.historyState.isLoading) return
        viewModelScope.launch {
            _ticketState.update { it.copy(historyState = it.historyState.copy(isLoading = true, errorMessage = "")) }
            current.fetchCardHistory(limit).fold(
                onSuccess = { items ->
                    _ticketState.update {
                        it.copy(historyState = CardHistoryState(items = items, lastUpdatedMs = System.currentTimeMillis()))
                    }
                },
                onFailure = {
                    _ticketState.update {
                        it.copy(historyState = it.historyState.copy(isLoading = false, errorMessage = app.getString(R.string.history_error)))
                    }
                }
            )
        }
    }

    // ============================================================ lifecycle

    fun onAppForegrounded() {
        isInForeground = true
        val state = _appState.value
        if (state.isPinSet && lastBackgroundTimeMs != 0L) {
            val timeoutMs = state.lockTimeoutSeconds * 1000L
            if (timeoutMs == 0L || System.currentTimeMillis() - lastBackgroundTimeMs >= timeoutMs) {
                _appState.update { it.copy(isAppUnlocked = false) }
            }
        }
        if (state.isLoggedIn && _ticketState.value.phase == TicketPhase.Paused) startPolling()
    }

    fun onAppBackgrounded() {
        isInForeground = false
        lastBackgroundTimeMs = System.currentTimeMillis()
        // No one can see the code in the background; stop using data and battery.
        if (pollingJob?.isActive == true) stopPolling(TicketPhase.Paused)
    }

    // ============================================================= security

    fun setPin(pin: String) {
        prefs.savePin(pin)
        _appState.update { it.copy(isPinSet = true, isAppUnlocked = true) }
    }

    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!prefs.verifyPin(currentPin)) return false
        prefs.savePin(newPin)
        return true
    }

    fun verifyPin(pin: String): Boolean {
        val ok = pin.isNotBlank() && prefs.verifyPin(pin)
        if (ok) _appState.update { it.copy(isAppUnlocked = true) }
        return ok
    }

    fun unlockWithBiometrics() = _appState.update { it.copy(isAppUnlocked = true) }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.saveBiometricEnabled(enabled)
        _appState.update { it.copy(biometricEnabled = enabled) }
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        prefs.saveLockTimeoutSeconds(seconds)
        _appState.update { it.copy(lockTimeoutSeconds = seconds) }
    }

    // ============================================================ reminders

    fun setLowCreditWarningThreshold(threshold: Double) {
        val normalized = threshold.coerceAtLeast(0.0)
        prefs.saveLowCreditWarningThreshold(normalized)
        _appState.update { it.copy(lowCreditWarningThreshold = normalized) }
    }

    fun setLowCreditAlerts(enabled: Boolean) {
        prefs.saveLowCreditAlertsEnabled(enabled)
        _appState.update { it.copy(lowCreditAlerts = enabled) }
        Reminders.reschedule(app)
    }

    fun setExpiryAlerts(enabled: Boolean) {
        prefs.saveExpiryAlertsEnabled(enabled)
        _appState.update { it.copy(expiryAlerts = enabled) }
        Reminders.reschedule(app)
    }

    // =========================================================== appearance

    fun setAmoledEnabled(enabled: Boolean) {
        prefs.saveAmoledEnabled(enabled)
        _appState.update { it.copy(amoledEnabled = enabled) }
    }

    fun selectThemePreset(presetId: String) {
        prefs.saveSelectedThemeId(presetId)
        _appState.update { it.copy(selectedThemeId = presetId) }
    }

    fun addThemePreset(name: String, primary: Long, secondary: Long, tertiary: Long) {
        val preset = ThemePreset(java.util.UUID.randomUUID().toString(), name, primary, secondary, tertiary)
        val updated = _appState.value.themePresets + preset
        prefs.saveThemePresets(updated.filter { p -> DefaultThemePresets.all.none { it.id == p.id } })
        prefs.saveSelectedThemeId(preset.id)
        _appState.update { it.copy(themePresets = updated, selectedThemeId = preset.id) }
    }

    fun setLanguageCode(languageCode: String) {
        prefs.saveLanguageCode(languageCode)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode))
        _appState.update { it.copy(languageCode = languageCode) }
    }

    // =========================================================== timetables

    fun setTimetableCity(citySlug: String) {
        _timetableState.update { it.copy(citySlug = normalizeTimetableCitySlug(citySlug)) }
    }

    fun setTimetableTime(time: String) = _timetableState.update { it.copy(timeInput = time) }

    fun setTimetableDirectOnly(directOnly: Boolean) = _timetableState.update { it.copy(directOnly = directOnly) }

    fun updateTimetableFromInput(fromInput: String) {
        _timetableState.update {
            it.copy(
                fromInput = fromInput,
                selectedFromSuggestion = it.selectedFromSuggestion?.takeIf { s -> s.selectedText == fromInput }
            )
        }
        requestTimetableSuggestions(fromInput, isFrom = true)
    }

    fun updateTimetableToInput(toInput: String) {
        _timetableState.update {
            it.copy(
                toInput = toInput,
                selectedToSuggestion = it.selectedToSuggestion?.takeIf { s -> s.selectedText == toInput }
            )
        }
        requestTimetableSuggestions(toInput, isFrom = false)
    }

    fun selectTimetableFromSuggestion(suggestion: CpStopSuggestion) {
        fromSuggestionJob?.cancel()
        _timetableState.update {
            it.copy(
                fromInput = suggestion.selectedText,
                selectedFromSuggestion = suggestion,
                fromSuggestions = emptyList(),
                isLoadingFromSuggestions = false
            )
        }
    }

    fun selectTimetableToSuggestion(suggestion: CpStopSuggestion) {
        toSuggestionJob?.cancel()
        _timetableState.update {
            it.copy(
                toInput = suggestion.selectedText,
                selectedToSuggestion = suggestion,
                toSuggestions = emptyList(),
                isLoadingToSuggestions = false
            )
        }
    }

    fun swapTimetableEnds() {
        fromSuggestionJob?.cancel()
        toSuggestionJob?.cancel()
        _timetableState.update {
            it.copy(
                fromInput = it.toInput,
                toInput = it.fromInput,
                selectedFromSuggestion = it.selectedToSuggestion,
                selectedToSuggestion = it.selectedFromSuggestion,
                fromSuggestions = emptyList(),
                toSuggestions = emptyList()
            )
        }
    }

    fun applyRoute(route: SavedRoute, search: Boolean = true) {
        fromSuggestionJob?.cancel()
        toSuggestionJob?.cancel()
        _timetableState.update {
            it.copy(
                citySlug = route.citySlug,
                fromInput = route.fromText,
                toInput = route.toText,
                selectedFromSuggestion = route.from,
                selectedToSuggestion = route.to,
                fromSuggestions = emptyList(),
                toSuggestions = emptyList()
            )
        }
        if (search) loadTimetables()
    }

    private fun currentRoute(): SavedRoute? {
        val s = _timetableState.value
        if (s.fromInput.isBlank() || s.toInput.isBlank()) return null
        return SavedRoute(
            citySlug = s.citySlug,
            fromText = s.fromInput.trim(),
            toText = s.toInput.trim(),
            from = s.selectedFromSuggestion?.takeIf { it.selectedText.equals(s.fromInput.trim(), true) },
            to = s.selectedToSuggestion?.takeIf { it.selectedText.equals(s.toInput.trim(), true) }
        )
    }

    fun toggleSaveCurrentRoute() {
        val route = currentRoute() ?: return
        val saved = _timetableState.value.savedRoutes
        val updated = if (saved.any { it.sameAs(route) }) {
            saved.filterNot { it.sameAs(route) }
        } else {
            (listOf(route) + saved).take(12)
        }
        prefs.saveSavedRoutes(updated)
        _timetableState.update { it.copy(savedRoutes = updated) }
    }

    fun removeSavedRoute(route: SavedRoute) {
        val updated = _timetableState.value.savedRoutes.filterNot { it.sameAs(route) }
        prefs.saveSavedRoutes(updated)
        _timetableState.update { it.copy(savedRoutes = updated) }
    }

    private fun rememberRecentRoute(route: SavedRoute) {
        val updated = (listOf(route) + _timetableState.value.recentRoutes.filterNot { it.sameAs(route) }).take(5)
        prefs.saveRecentRoutes(updated)
        _timetableState.update { it.copy(recentRoutes = updated) }
    }

    private fun requestTimetableSuggestions(query: String, isFrom: Boolean) {
        val trimmed = query.trim()
        if (isFrom) fromSuggestionJob?.cancel() else toSuggestionJob?.cancel()
        if (trimmed.length < 2) {
            _timetableState.update {
                if (isFrom) it.copy(fromSuggestions = emptyList(), isLoadingFromSuggestions = false)
                else it.copy(toSuggestions = emptyList(), isLoadingToSuggestions = false)
            }
            return
        }
        val job = viewModelScope.launch {
            delay(250)
            _timetableState.update {
                if (isFrom) it.copy(isLoadingFromSuggestions = true) else it.copy(isLoadingToSuggestions = true)
            }
            val city = _timetableState.value.citySlug
            val suggestions = withContext(Dispatchers.IO) {
                cpTimetableService.suggestStops(city, trimmed)
            }.getOrDefault(emptyList())
            _timetableState.update {
                if (isFrom) it.copy(fromSuggestions = suggestions, isLoadingFromSuggestions = false)
                else it.copy(toSuggestions = suggestions, isLoadingToSuggestions = false)
            }
        }
        if (isFrom) fromSuggestionJob = job else toSuggestionJob = job
    }

    fun loadTimetables() {
        val route = currentRoute() ?: return
        val state = _timetableState.value
        rememberRecentRoute(route)
        viewModelScope.launch {
            _timetableState.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    canLoadMore = false,
                    pagingCursor = null,
                    connections = emptyList(),
                    errorMessage = "",
                    fromSuggestions = emptyList(),
                    toSuggestions = emptyList()
                )
            }
            val time = state.timeInput.ifBlank { currentTimeText() }
            val result = withContext(Dispatchers.IO) {
                cpTimetableService.searchConnections(
                    CpTimetableService.SearchRequest(
                        citySlug = state.citySlug,
                        fromInput = route.fromText,
                        toInput = route.toText,
                        timeInput = time,
                        directOnly = state.directOnly,
                        fromSuggestion = route.from,
                        toSuggestion = route.to
                    )
                )
            }
            result.fold(
                onSuccess = { searchResult ->
                    val connections = if (state.directOnly) {
                        searchResult.connections.filter { it.isDirect }
                    } else {
                        searchResult.connections
                    }.sortedBy { it.departureSortKey }
                    _timetableState.update {
                        it.copy(
                            isLoading = false,
                            connections = connections,
                            pagingCursor = searchResult.pagingCursor,
                            canLoadMore = searchResult.pagingCursor != null,
                            errorMessage = if (connections.isEmpty()) app.getString(R.string.timetables_no_results) else ""
                        )
                    }
                },
                onFailure = {
                    _timetableState.update {
                        it.copy(isLoading = false, errorMessage = app.getString(R.string.timetables_error))
                    }
                }
            )
        }
    }

    fun loadMoreTimetables() {
        val current = _timetableState.value
        val cursor = current.pagingCursor ?: return
        if (current.isLoading || current.isLoadingMore) return
        viewModelScope.launch {
            _timetableState.update { it.copy(isLoadingMore = true) }
            val result = withContext(Dispatchers.IO) { cpTimetableService.loadMoreConnections(cursor) }
            result.fold(
                onSuccess = { searchResult ->
                    _timetableState.update { state ->
                        val newItems = searchResult.connections
                            .let { list -> if (state.directOnly) list.filter { it.isDirect } else list }
                        state.copy(
                            isLoadingMore = false,
                            connections = (state.connections + newItems).distinctBy { it.id }.sortedBy { it.departureSortKey },
                            pagingCursor = searchResult.pagingCursor,
                            canLoadMore = searchResult.pagingCursor?.allowNext == true && searchResult.connections.isNotEmpty()
                        )
                    }
                },
                onFailure = { _timetableState.update { it.copy(isLoadingMore = false, canLoadMore = false) } }
            )
        }
    }

    private fun normalizeTimetableCitySlug(citySlug: String): String = when (citySlug.trim().lowercase()) {
        "", "slovakia" -> "slovensko"
        "banska-bystrica", "banská-bystrica", "banska bystrica", "banská bystrica" -> "banskabystrica"
        else -> citySlug.trim().lowercase()
    }

    private fun currentTimeText(): String {
        val now = java.util.Calendar.getInstance()
        return "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
    }
}
