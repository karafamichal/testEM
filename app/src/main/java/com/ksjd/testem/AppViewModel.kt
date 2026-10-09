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
    /** A SADZV account is saved on this phone. Without one the app is departures and planner only. */
    val hasAccount: Boolean = false,
    /** The welcome screen was answered (account added or skipped). */
    val onboarded: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val loginError: String = "",
    val email: String = "",
    val password: String = "",
    val themePresets: List<ThemePreset> = emptyList(),
    val selectedThemeId: String = "",
    val amoledEnabled: Boolean = false,
    val themeMode: String = "system",
    val backgroundColor: Long? = null,
    val isPinSet: Boolean = false,
    /** Ticket and account details may be shown (PIN or biometrics given, or no PIN at all). */
    val isAppUnlocked: Boolean = false,
    val biometricEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 0,
    val lowCreditWarningThreshold: Double = 1.0,
    val lowCreditAlerts: Boolean = false,
    val expiryAlerts: Boolean = false,
    val languageCode: String = "sk",
    /** An eshop.idsbbsk.sk account is saved too (EM-0023). */
    val hasIdsAccount: Boolean = false,
    val idsEmail: String = "",
    /** "sadzv"/"ids": whose email fills in reports when the two differ ("" = not asked yet). */
    val primaryAccount: String = "",
    /** Switch the shown ticket by the followed bus. */
    val ticketAuto: Boolean = true,
    val ticketDefault: Wallet = Wallet.SADZV,
    /** History of one account, or both merged (null). */
    val historyWallet: Wallet? = null
) {
    /** Some account is saved: the ticket, account and history (behind the PIN) exist. */
    val anyAccount: Boolean get() = hasAccount || hasIdsAccount
    val bothAccounts: Boolean get() = hasAccount && hasIdsAccount

    /** Both saved under different emails and the rider hasn't said which is theirs. */
    val needsPrimary: Boolean
        get() = bothAccounts && primaryAccount.isEmpty() && !email.trim().equals(idsEmail.trim(), ignoreCase = true)

    /** The email filled in reports and suggestions. */
    val contactEmail: String
        get() = when {
            bothAccounts -> if (primaryAccount == "ids") idsEmail else email
            hasIdsAccount -> idsEmail
            else -> email
        }
}

data class IdsState(
    val account: IdsAccount? = null,
    /** The virtual card's QR code (static), kept for offline use. */
    val qr: Bitmap? = null,
    val isLoading: Boolean = false,
    /** Couldn't reach the e-shop at the last refresh (cached data shown). */
    val offline: Boolean = false,
    val signedOut: Boolean = false
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = CredentialsManager(application)
    private val timetableService = TimetableService()

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState

    private val _ticketState = MutableStateFlow(TicketState())
    val ticketState: StateFlow<TicketState> = _ticketState

    private val _timetableState = MutableStateFlow(TimetableState())
    val timetableState: StateFlow<TimetableState> = _timetableState

    private val _idsState = MutableStateFlow(IdsState())
    val idsState: StateFlow<IdsState> = _idsState

    private var session: TicketSession? = null
    private var idsSession: IdsSession? = null
    private var pollingJob: Job? = null
    private var isInForeground = true
    private var lastBackgroundTimeMs = 0L
    private var fromSuggestionJob: Job? = null
    private var toSuggestionJob: Job? = null
    /** Route of the results on screen (the fields may have been edited since). */
    private var searchedRoute: SavedRoute? = null

    init {
        val presets = prefs.getThemePresets(DefaultThemePresets.all)
        val hasAccount = prefs.hasAccount()
        val hasIds = prefs.hasIdsAccount()
        // The PIN guards the accounts only: without one there is nothing to lock.
        if (!hasAccount && !hasIds && prefs.isPinSet()) prefs.clearPin()
        val pinSet = prefs.isPinSet()
        _appState.value = AppState(
            hasAccount = hasAccount,
            onboarded = prefs.isOnboarded(),
            email = prefs.getEmail(),
            password = prefs.getPassword(),
            themePresets = presets,
            selectedThemeId = prefs.getSelectedThemeId(presets.first().id),
            amoledEnabled = prefs.getAmoledEnabled(),
            themeMode = prefs.getThemeMode(),
            backgroundColor = prefs.getBackgroundColor(),
            isPinSet = pinSet,
            isAppUnlocked = !pinSet,
            biometricEnabled = prefs.getBiometricEnabled(),
            lockTimeoutSeconds = prefs.getLockTimeoutSeconds(),
            lowCreditWarningThreshold = prefs.getLowCreditWarningThreshold(),
            lowCreditAlerts = prefs.getLowCreditAlertsEnabled(),
            expiryAlerts = prefs.getExpiryAlertsEnabled(),
            languageCode = prefs.getLanguageCode(),
            hasIdsAccount = hasIds,
            idsEmail = prefs.getIdsEmail(),
            primaryAccount = prefs.getPrimaryAccount(),
            ticketAuto = prefs.getTicketAuto(),
            ticketDefault = prefs.getTicketDefault(),
            historyWallet = prefs.getHistoryWallet()
        )
        if (hasIds) _idsState.value = IdsState(account = prefs.getLastIdsAccount(), qr = idsQrBitmap(prefs.getIdsQr()))
        prefs.getLastAccount()?.let { cached ->
            _ticketState.update {
                it.copy(userName = cached.userName, cards = cached.cards, selectedSnr = prefs.getSelectedCardSnr())
            }
        }
        _timetableState.update {
            it.copy(
                savedRoutes = prefs.getSavedRoutes(),
                recentRoutes = prefs.getRecentRoutes(),
                savedJourneys = prefs.getSavedJourneys(),
                rides = prefs.getRecurringRides()
            )
        }
    }

    // ============================================================ onboarding

    /** "Use without an account" on the welcome screen; an account can be added later in Settings. */
    fun skipOnboarding() {
        prefs.markOnboarded()
        _appState.update { it.copy(onboarded = true, loginError = "") }
    }

    // ================================================================ login

    fun hasSavedCredentials(): Boolean = _appState.value.let { it.email.isNotBlank() && it.password.isNotBlank() }

    fun loginWithSavedCredentials() {
        val state = _appState.value
        if (state.isLoading || state.isLoggedIn || !hasSavedCredentials()) return
        login(state.email, state.password)
    }

    fun login(email: String, password: String, wallet: Wallet = Wallet.SADZV) {
        val emailTrimmed = email.trim()
        if (emailTrimmed.isEmpty() || password.isEmpty()) {
            _appState.update { it.copy(loginError = app.getString(R.string.login_error_fill_fields)) }
            return
        }
        if (wallet == Wallet.IDS) return loginIds(emailTrimmed, password)
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
                    it.copy(isLoggedIn = true, isLoading = false, email = emailTrimmed, password = password, hasAccount = true)
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

    /**
     * The server stopped accepting the saved password: forget it (email kept) and show the
     * sign-in form. The PIN stays, so signing in again doesn't ask for a new one.
     */
    fun returnToLogin() {
        stopPolling()
        session = null
        prefs.saveCredentials(_appState.value.email, "")
        _appState.update { it.copy(isLoggedIn = false, isLoading = false, password = "", hasAccount = false) }
        _ticketState.value = TicketState()
    }

    /**
     * Removes the account from this phone: credentials, cached account data, reminders
     * and the PIN (which only protected the account). The app keeps working without it.
     */
    fun removeAccount() {
        stopPolling()
        session = null
        prefs.clearCredentials()
        Reminders.cancel(app)
        _appState.update {
            it.copy(isLoggedIn = false, isLoading = false, loginError = "", email = "", password = "", hasAccount = false)
        }
        _ticketState.value = TicketState()
        afterAccountRemoved()
    }

    /** Cancelling the PIN on first setup: nothing that was added stays without a PIN. */
    fun removeAllAccounts() {
        removeIdsAccount()
        removeAccount()
    }

    /** The PIN only protected the accounts: with none left it goes too. */
    private fun afterAccountRemoved() {
        prefs.savePrimaryAccount("")
        _appState.update { it.copy(primaryAccount = "") }
        if (_appState.value.anyAccount) return
        prefs.clearPin()
        _appState.update { it.copy(isPinSet = false, isAppUnlocked = true) }
    }

    // ======================================================== IDS BBSK (EM-0023)

    private fun loginIds(email: String, password: String) {
        _appState.update { it.copy(isLoading = true, loginError = "") }
        val newSession = IdsSession(email, password)
        viewModelScope.launch {
            runCatching { newSession.fetchAccount() }.onSuccess { account ->
                idsSession = newSession
                prefs.saveIdsCredentials(email, password)
                _appState.update { it.copy(isLoading = false, hasIdsAccount = true, idsEmail = email) }
                _idsState.value = IdsState(account = account)
                prefs.saveLastIdsAccount(account)
                loadIdsQr(newSession, account)
            }.onFailure { error ->
                val message = app.getString(if (error is LoginRejectedException) R.string.ids_login_rejected else R.string.login_error_network)
                _appState.update { it.copy(isLoading = false, loginError = message) }
            }
        }
    }

    /** New credit, tickets and identifiers from the e-shop (the QR code is fetched once: it never changes). */
    fun refreshIds() {
        val state = _appState.value
        if (!state.hasIdsAccount || _idsState.value.isLoading) return
        var current = idsSession ?: IdsSession(prefs.getIdsEmail(), prefs.getIdsPassword()).also { idsSession = it }
        _idsState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val name = _idsState.value.account?.name.orEmpty()
            runCatching { current.fetchAccount(name) }.recoverCatching { error ->
                // EM-0047: one refusal right after unlocking isn't a changed password. Try once more from scratch.
                if (error !is LoginRejectedException) throw error
                delay(2_000)
                current = IdsSession(prefs.getIdsEmail(), prefs.getIdsPassword()).also { idsSession = it }
                current.fetchAccount(name)
            }.onSuccess { account ->
                prefs.saveLastIdsAccount(account)
                _idsState.update { it.copy(account = account, isLoading = false, offline = false, signedOut = false) }
                if (_idsState.value.qr == null) loadIdsQr(current, account)
            }.onFailure { error ->
                _idsState.update { it.copy(isLoading = false, offline = error !is LoginRejectedException, signedOut = error is LoginRejectedException) }
            }
        }
    }

    private suspend fun loadIdsQr(current: IdsSession, account: IdsAccount) {
        val id = account.qrIdentifier?.qrId ?: return
        val content = runCatching { current.fetchQr(id) }.getOrNull() ?: return
        prefs.saveIdsQr(content)
        _idsState.update { it.copy(qr = idsQrBitmap(content)) }
    }

    /** The stored QR: the e-shop's own picture ("img:<base64>") or the code's text ("text:…") drawn here. */
    private fun idsQrBitmap(content: String): Bitmap? = when {
        content.startsWith("img:") -> runCatching {
            val bytes = android.util.Base64.decode(content.removePrefix("img:"), android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        content.startsWith("text:") ->
            QRCodeGenerator.generateQRCode(content.removePrefix("text:"), QRDaemonConfig.QR_BITMAP_SIZE, QRDaemonConfig.QR_BITMAP_SIZE)
        else -> null
    }

    fun removeIdsAccount() {
        idsSession = null
        prefs.clearIdsCredentials()
        _idsState.value = IdsState()
        _appState.update { it.copy(hasIdsAccount = false, idsEmail = "") }
        afterAccountRemoved()
    }

    fun setPrimaryAccount(account: String) {
        prefs.savePrimaryAccount(account)
        _appState.update { it.copy(primaryAccount = account) }
    }

    fun setTicketAuto(enabled: Boolean) {
        prefs.saveTicketAuto(enabled)
        _appState.update { it.copy(ticketAuto = enabled) }
    }

    fun setTicketDefault(wallet: Wallet) {
        prefs.saveTicketDefault(wallet)
        _appState.update { it.copy(ticketDefault = wallet) }
    }

    fun setHistoryWallet(wallet: Wallet?) {
        prefs.saveHistoryWallet(wallet)
        _appState.update { it.copy(historyWallet = wallet) }
    }

    fun clearLoginError() = _appState.update { it.copy(loginError = "") }

    // ============================================================== polling

    fun startPolling() {
        val current = session ?: return
        if (pollingJob?.isActive == true || !_appState.value.isAppUnlocked) return
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

    suspend fun topUpLimits(): TicketSession.TopUpLimits = (session ?: throw java.io.IOException("Signed out")).topUpLimits()

    /** Creates the top-up order for the selected card; the rider pays it on the returned page. */
    suspend fun startTopUp(limits: TicketSession.TopUpLimits, cents: Int): TicketSession.PaymentGate {
        val current = session ?: throw java.io.IOException("Signed out")
        return current.startTopUp(current.activeSnr, limits.tariffId, cents)
    }

    fun topUpCookies(): List<Pair<String, String>> = session?.webCookies().orEmpty()

    /** After the payment page: the new balance and the top-up in History. */
    fun afterTopUp() {
        refreshAccount()
        loadCardHistory()
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
            if (timeoutMs == 0L || System.currentTimeMillis() - lastBackgroundTimeMs >= timeoutMs) lock()
        }
        val now = _appState.value
        if (now.isLoggedIn && now.isAppUnlocked && _ticketState.value.phase == TicketPhase.Paused) startPolling()
    }

    /** Hides the ticket and account again (the rest of the app stays usable). */
    fun lock() {
        if (!_appState.value.isPinSet) return
        _appState.update { it.copy(isAppUnlocked = false) }
        if (pollingJob?.isActive == true) stopPolling(TicketPhase.Paused)
    }

    private fun onUnlocked() {
        _appState.update { it.copy(isAppUnlocked = true) }
        refreshIds()
        if (_appState.value.isLoggedIn && pollingJob?.isActive != true) startPolling()
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
        if (ok) onUnlocked()
        return ok
    }

    /** Wrong PINs lock entry for a while; 0 when a PIN may be tried now. */
    fun pinLockedUntil(): Long = prefs.pinLockedUntil().takeIf { it > System.currentTimeMillis() } ?: 0L

    fun unlockWithBiometrics() = onUnlocked()

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

    fun setThemeMode(mode: String) {
        prefs.saveThemeMode(mode)
        _appState.update { it.copy(themeMode = mode) }
    }

    fun setBackgroundColor(color: Long?) {
        prefs.saveBackgroundColor(color)
        _appState.update { it.copy(backgroundColor = color) }
    }

    fun selectThemePreset(presetId: String) {
        prefs.saveSelectedThemeId(presetId)
        _appState.update { it.copy(selectedThemeId = presetId) }
        com.ksjd.testem.live.TripTracking.refreshWatch(app) // the watch app and the widget take the new colours
    }

    fun addThemePreset(name: String, primary: Long, secondary: Long, tertiary: Long) {
        val preset = ThemePreset(java.util.UUID.randomUUID().toString(), name, primary, secondary, tertiary)
        val updated = _appState.value.themePresets + preset
        prefs.saveThemePresets(updated.filter { p -> DefaultThemePresets.all.none { it.id == p.id } })
        prefs.saveSelectedThemeId(preset.id)
        _appState.update { it.copy(themePresets = updated, selectedThemeId = preset.id) }
        com.ksjd.testem.live.TripTracking.refreshWatch(app)
    }

    fun setLanguageCode(languageCode: String) {
        // The watch app speaks the phone app's language: it learns it with the next update.
        com.ksjd.testem.live.TripTracking.refreshWatch(app)
        prefs.saveLanguageCode(languageCode)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode))
        _appState.update { it.copy(languageCode = languageCode) }
    }

    // =========================================================== timetables

    fun setTimetableTime(time: String) = _timetableState.update { it.copy(timeInput = time) }

    /** A result with its change moved to another stop by the rider (EM-0026, EM-0030). */
    fun replaceConnection(old: TimetableConnection, new: TimetableConnection) = _timetableState.update { s ->
        s.copy(connections = s.connections.map { if (it.id == old.id) new else it })
    }

    /** The planner bus whose stops are open (connection, bus index), so a stop tapped there edits the connection. */
    private var openLeg: Pair<TimetableConnection, Int>? = null

    fun openedPlannerLeg(connection: TimetableConnection, busIndex: Int) { openLeg = connection to busIndex }
    fun closedPlannerLeg() { openLeg = null }

    /**
     * A stop tapped in an open planner bus (EM-0039): get off there and plan the rest from it, like the transfer
     * editor. The last bus has nothing to re-plan; there the tap only marks where following ends.
     */
    fun getOffAt(order: Int) {
        val (connection, busIndex) = openLeg ?: return
        if (busIndex >= connection.segments.lastIndex) return
        viewModelScope.launch {
            val edited = withContext(Dispatchers.IO) { runCatching { timetableService.getOffAt(connection, busIndex, order) }.getOrNull() }
            if (edited == null) {
                android.widget.Toast.makeText(app, R.string.transfer_none_from, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            replaceConnection(connection, edited)
            if (openLeg?.first?.id == connection.id) openLeg = edited to busIndex
        }
    }

    /** ISO date, "" = today. */
    fun setTimetableDate(date: String) = _timetableState.update { it.copy(dateInput = date) }

    // ------------------------------------------------------------ saved journeys

    fun isJourneySaved(connection: TimetableConnection): Boolean =
        _timetableState.value.savedJourneys.any { it.sameAs(connection) }

    /** Star / unstar a result; starred ones show under the ticket on the home tab. */
    fun toggleSaveJourney(connection: TimetableConnection, route: SavedRoute? = null) {
        val saved = _timetableState.value.savedJourneys
        val updated = if (saved.any { it.sameAs(connection) }) {
            saved.filterNot { it.sameAs(connection) }
        } else {
            val route = route ?: searchedRoute ?: currentRoute() ?: return
            // Journeys that already ended are of no use on the home tab.
            val now = System.currentTimeMillis()
            (saved.filter { it.arrivalMs == 0L || it.arrivalMs > now } +
                SavedJourney(java.util.UUID.randomUUID().toString(), route, connection))
                .sortedBy { it.departureMs }
                .takeLast(20)
        }
        prefs.saveSavedJourneys(updated)
        com.ksjd.testem.reminders.LeaveBy.reschedule(app)
        _timetableState.update { it.copy(savedJourneys = updated) }
    }

    fun removeSavedJourney(id: String) {
        val updated = _timetableState.value.savedJourneys.filterNot { it.id == id }
        prefs.saveSavedJourneys(updated)
        com.ksjd.testem.reminders.LeaveBy.reschedule(app)
        _timetableState.update { it.copy(savedJourneys = updated) }
    }

    /** Opens the planner on a route at a given time and date and searches. */
    fun searchAt(route: SavedRoute, time: String, date: String = "") {
        _timetableState.update { it.copy(timeInput = time, dateInput = date) }
        applyRoute(route, search = true)
    }

    // ------------------------------------------------------------ recurring rides

    /** A ride from a planner result, prefilled for the editor: same stops, time and lines, workdays. */
    fun rideFrom(connection: TimetableConnection, route: SavedRoute? = null): RecurringRide? {
        val route = route ?: searchedRoute ?: currentRoute() ?: return null
        return RecurringRide(
            id = java.util.UUID.randomUUID().toString(),
            route = route,
            time = connection.segments.firstOrNull()?.departureTime?.let { t -> parseClock(t)?.toString() } ?: return null,
            lines = connection.segments.map { it.line.trim().substringAfterLast(' ') },
            days = RecurringRide.WORKDAYS
        )
    }

    // ------------------------------------------------------------ the user's own journey (built bus by bus)

    /** The journey built from boards as a planner connection, or null while a bus has no stops. */
    fun draftConnection(legs: List<com.ksjd.testem.live.DraftLeg>): TimetableConnection? {
        val segments = legs.map { it.segment ?: return null }.takeIf { it.isNotEmpty() } ?: return null
        val first = segments.first()
        val last = segments.last()
        val minutes = ((SavedJourney.epochMs(last.serviceDate, last.arrivalTime) - SavedJourney.epochMs(first.serviceDate, first.departureTime)) / 60_000L)
            .let { if (it < 0) it + 24 * 60 else it }.toInt()
        return TimetableConnection(
            id = "draft-" + segments.joinToString("|") { it.routeUrl.ifBlank { it.line + it.departureTime } }.hashCode(),
            departureTime = first.departureTime,
            arrivalTime = last.arrivalTime,
            totalDuration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min",
            segments = segments
        )
    }

    /** Where the built journey starts and ends, as timetable stops found by position, so the planner can search it again. */
    private suspend fun draftRoute(legs: List<com.ksjd.testem.live.DraftLeg>): SavedRoute? = withContext(Dispatchers.IO) {
        val first = legs.firstOrNull() ?: return@withContext null
        val last = legs.last()
        val start = first.segment?.departureStop ?: return@withContext null
        val end = last.segment?.arrivalStop ?: return@withContext null
        fun stop(name: String, at: Pair<Double, Double>?) =
            at?.let { runCatching { timetableService.intercityStopNear(name, it.first, it.second) }.getOrNull() }
        val from = stop(start, first.from)
        val to = stop(end, last.to)
        SavedRoute(PLANNER_ALL, from?.selectedText ?: readableStopName(start), to?.selectedText ?: readableStopName(end), from, to)
    }

    /** Star / unstar the user's own journey, like a planner result. */
    fun toggleSaveDraft(legs: List<com.ksjd.testem.live.DraftLeg>) {
        val connection = draftConnection(legs) ?: return
        if (isJourneySaved(connection)) return toggleSaveJourney(connection)
        viewModelScope.launch { toggleSaveJourney(connection, draftRoute(legs) ?: return@launch) }
    }

    /** A regular ride from the user's own journey, prefilled for the editor. */
    fun rideFromDraft(legs: List<com.ksjd.testem.live.DraftLeg>, onReady: (RecurringRide) -> Unit) {
        val connection = draftConnection(legs) ?: return
        viewModelScope.launch { draftRoute(legs)?.let { route -> rideFrom(connection, route)?.let(onReady) } }
    }

    fun saveRide(ride: RecurringRide) {
        val updated = _timetableState.value.rides.filterNot { it.id == ride.id } + ride
        prefs.saveRecurringRides(updated)
        _timetableState.update { it.copy(rides = updated) }
        com.ksjd.testem.rides.RideScheduler.reschedule(app)
    }

    fun deleteRide(id: String) {
        val updated = _timetableState.value.rides.filterNot { it.id == id }
        prefs.saveRecurringRides(updated)
        _timetableState.update { it.copy(rides = updated) }
        com.ksjd.testem.rides.RideScheduler.reschedule(app)
    }

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
            // Routes saved before 2.5 used the intercity-only timetable; its stop ids differ, so look them up again.
            val old = route.citySlug == "slovensko"
            it.copy(
                citySlug = if (old) PLANNER_ALL else route.citySlug,
                fromInput = route.fromText,
                toInput = route.toText,
                selectedFromSuggestion = route.from.takeUnless { old },
                selectedToSuggestion = route.to.takeUnless { old },
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
                timetableService.suggestStops(city, trimmed)
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
        searchedRoute = route
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
                timetableService.searchConnections(
                    TimetableService.SearchRequest(
                        citySlug = state.citySlug,
                        fromInput = route.fromText,
                        toInput = route.toText,
                        timeInput = time,
                        directOnly = state.directOnly,
                        fromSuggestion = route.from,
                        toSuggestion = route.to,
                        dateInput = state.dateInput
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
            val result = withContext(Dispatchers.IO) { timetableService.loadMoreConnections(cursor) }
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


    private fun currentTimeText(): String {
        val now = java.time.LocalTime.now(SK_ZONE)
        return "%02d:%02d".format(now.hour, now.minute)
    }
}
