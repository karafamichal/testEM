package com.ksjd.testem

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.luminance
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ksjd.testem.live.LiveViewModel
import com.ksjd.testem.ui.screens.AccountScreen
import com.ksjd.testem.ui.screens.DeparturesScreen
import com.ksjd.testem.ui.screens.HistoryScreen
import com.ksjd.testem.ui.screens.LockedScreen
import com.ksjd.testem.ui.screens.WelcomeScreen
import com.ksjd.testem.ui.screens.PrimaryEmailDialog
import com.ksjd.testem.ui.screens.LoginScreen
import com.ksjd.testem.ui.screens.PinSetupScreen
import com.ksjd.testem.ui.screens.PlannerScreen
import com.ksjd.testem.ui.screens.TicketScreen
import com.ksjd.testem.ui.screens.UnlockDialog
import com.ksjd.testem.ui.theme.TestEMTheme

class MainActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        // Following stopped by an app update: pick the bus up again.
        com.ksjd.testem.live.TripTracking.resume(this)
        // A followed bus started from an alarm has no GPS in the background until the app is seen once.
        com.ksjd.testem.live.TripTracking.promote(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the saved language before any UI is inflated.
        val languageCode = CredentialsManager(this).getLanguageCode()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // AppCompat ignores this before an activity exists (fresh install): English UI, "Slovenčina" in Settings.
            val locales = getSystemService(android.app.LocaleManager::class.java)
            if (locales.applicationLocales.toLanguageTags() != languageCode) {
                locales.applicationLocales = android.os.LocaleList.forLanguageTags(languageCode)
            }
        } else if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != languageCode) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode))
        }
        com.ksjd.testem.ui.components.Haptics.enabled = CredentialsManager(this).getHapticsEnabled()
        TimetableService.changeMinutes = CredentialsManager(this).getChangeMinutes()
        // Cheap, and catches a changed alarm permission or a missed reboot.
        com.ksjd.testem.rides.RideScheduler.reschedule(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val viewModel: AppViewModel = viewModel()
            val liveViewModel: LiveViewModel = viewModel()
            val appState by viewModel.appState.collectAsState()
            val preset = appState.themePresets.firstOrNull { it.id == appState.selectedThemeId }
                ?: appState.themePresets.firstOrNull()
            // EM-0042: light/dark chosen in Appearance; a custom background (or a dark theme's own) decides it by its brightness.
            // A dark theme's own background, or any background the rider picked for the theme.
            val shipped = com.ksjd.testem.DefaultThemePresets.all.firstOrNull { it.id == preset?.id }
                ?.roles?.get(com.ksjd.testem.ThemeRole.BACKGROUND)
            val themeBackground = preset?.roles?.get(com.ksjd.testem.ThemeRole.BACKGROUND)
                ?.takeIf { androidx.compose.ui.graphics.Color(it).luminance() < 0.5f || it != shipped }
            val dark = (appState.backgroundColor ?: themeBackground)?.let { androidx.compose.ui.graphics.Color(it).luminance() < 0.5f }
                ?: when (appState.themeMode) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            androidx.compose.runtime.LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    androidx.activity.SystemBarStyle.auto(transparent, transparent) { dark },
                    androidx.activity.SystemBarStyle.auto(transparent, transparent) { dark }
                )
            }
            TestEMTheme(darkTheme = dark, themePreset = preset, amoledMode = appState.amoledEnabled, background = appState.backgroundColor) {
                AppRoot(viewModel, liveViewModel, this)
            }
        }
    }

    // The app was already open when the follow notification was tapped.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
}

/** Tapping the follow notification: show that bus over the home tab. */
private fun handleIntent(intent: android.content.Intent?) {
    if (intent?.getBooleanExtra(com.ksjd.testem.live.TripTracking.EXTRA_SHOW_FOLLOWED, false) == true) {
        intent.removeExtra(com.ksjd.testem.live.TripTracking.EXTRA_SHOW_FOLLOWED) // not again after rotation
        com.ksjd.testem.live.TripTracking.requestShow()
    }
    // "Add credit" on the low-credit alert: Account, with the top-up open.
    if (intent?.getBooleanExtra(com.ksjd.testem.reminders.Reminders.EXTRA_TOP_UP, false) == true) {
        intent.removeExtra(com.ksjd.testem.reminders.Reminders.EXTRA_TOP_UP)
        com.ksjd.testem.reminders.Reminders.topUpRequested.value = true
    }
    // Logs arrived from the watch: straight to the bug report form.
    if (intent?.getBooleanExtra(com.ksjd.testem.live.WatchSync.EXTRA_REPORT, false) == true) {
        intent.removeExtra(com.ksjd.testem.live.WatchSync.EXTRA_REPORT)
        com.ksjd.testem.hub.AppLogs.fromWatch = true
        com.ksjd.testem.hub.AppLogs.reportRequested.value = true
    }
}

enum class AppTab(val labelRes: Int) {
    Home(R.string.tab_home),
    Departures(R.string.tab_departures),
    Planner(R.string.tab_planner),
    History(R.string.tab_history),
    Account(R.string.tab_account)
}

@Composable
fun AppRoot(viewModel: AppViewModel, liveViewModel: LiveViewModel, activity: FragmentActivity) {
    val appState by viewModel.appState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Sign-in form opened from Settings ("Add account") or the home tab ("Sign in again"): for which account.
    var signingIn by rememberSaveable { mutableStateOf<Wallet?>(null) }

    LaunchedEffect(appState.isAppUnlocked, appState.hasAccount, appState.isLoggedIn, appState.isLoading, appState.email) {
        if (appState.isAppUnlocked && appState.hasAccount && !appState.isLoggedIn && !appState.isLoading &&
            appState.loginError.isBlank() && viewModel.hasSavedCredentials()
        ) {
            viewModel.loginWithSavedCredentials()
        }
    }
    LaunchedEffect(appState.hasAccount, appState.hasIdsAccount) {
        if (signingIn == Wallet.SADZV && appState.hasAccount || signingIn == Wallet.IDS && appState.hasIdsAccount) signingIn = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.onAppForegrounded()
                    liveViewModel.resume()
                }
                Lifecycle.Event.ON_STOP -> if (!activity.isChangingConfigurations) {
                    viewModel.onAppBackgrounded()
                    liveViewModel.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        // First run: add the SAD Zvolen and/or IDS BBSK account now, or use the app without them.
        !appState.onboarded -> WelcomeScreen(viewModel, appState)
        // An account was just added: its ticket needs a PIN.
        appState.anyAccount && !appState.isPinSet -> PinSetupScreen(viewModel)
        signingIn != null -> {
            val close = { signingIn = null; viewModel.clearLoginError() }
            BackHandler(onBack = close)
            LoginScreen(viewModel, appState, signingIn!!, welcome = false, onSkip = close)
        }
        else -> {
            MainScaffold(viewModel, liveViewModel, activity, onSignIn = { signingIn = it })
            // Two accounts under different emails: which one should reports use?
            if (appState.needsPrimary && appState.isAppUnlocked) PrimaryEmailDialog(appState) { viewModel.setPrimaryAccount(it) }
        }
    }
}

@Composable
private fun MainScaffold(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    activity: FragmentActivity,
    onSignIn: (Wallet) -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    // History exists only with an account; the last tab is Account or just Settings.
    val tabs = AppTab.entries.filter { it != AppTab.History || appState.anyAccount }
    var selected by rememberSaveable { mutableStateOf(AppTab.Home) }
    if (selected !in tabs) selected = AppTab.Home
    var unlocking by remember { mutableStateOf(false) }
    val requestUnlock = { unlocking = true }
    if (unlocking) UnlockDialog(viewModel, appState, activity, onDismiss = { unlocking = false })

    BackHandler(enabled = selected != AppTab.Home) { selected = AppTab.Home }
    val reportRequested by com.ksjd.testem.hub.AppLogs.reportRequested.collectAsState()
    LaunchedEffect(reportRequested) { if (reportRequested) selected = AppTab.Account }
    val topUpRequested by com.ksjd.testem.reminders.Reminders.topUpRequested.collectAsState()
    LaunchedEffect(topUpRequested) { if (topUpRequested) selected = AppTab.Account }

    // Follow notification tapped: home tab (the ticket is one swipe away) with the bus's stops on top.
    val showFollowed by com.ksjd.testem.live.TripTracking.showRequested.collectAsState()
    LaunchedEffect(showFollowed) {
        if (!showFollowed) return@LaunchedEffect
        com.ksjd.testem.live.TripTracking.showHandled()
        selected = AppTab.Home
        com.ksjd.testem.live.TripTracking.active.value?.let(liveViewModel::openFollowed)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // The bar floats over the content (which scrolls on under it), so only the top inset is padded here.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            val openStopFromPlanner: (String, String, String) -> Unit = { stopName, line, time ->
                selected = AppTab.Departures
                liveViewModel.openStopByName(
                    stopName,
                    com.ksjd.testem.live.BoardHighlight(line, time),
                    onNotFound = { liveViewModel.search(stopName.substringAfterLast(",").trim()) }
                )
            }
            when (selected) {
                AppTab.Home -> TicketScreen(
                    viewModel = viewModel,
                    liveViewModel = liveViewModel,
                    activity = activity,
                    onUnlock = requestUnlock,
                    onSignIn = onSignIn,
                    onOpenDepartures = { selected = AppTab.Departures },
                    onOpenPlanner = { selected = AppTab.Planner },
                    onOpenAccount = { selected = AppTab.Account }
                )
                AppTab.Departures -> DeparturesScreen(liveViewModel = liveViewModel)
                AppTab.Planner -> PlannerScreen(
                    viewModel = viewModel,
                    onOpenStop = openStopFromPlanner,
                    onOpenTrip = liveViewModel::openScheduledTrip
                )
                AppTab.History -> if (appState.isAppUnlocked) HistoryScreen(viewModel = viewModel) else LockedScreen(requestUnlock)
                AppTab.Account -> AccountScreen(viewModel = viewModel, onUnlock = requestUnlock, onSignIn = onSignIn)
            }
            // First run: ask about the two opt-in features (community delays, catch estimate).
            val context = androidx.compose.ui.platform.LocalContext.current
            var privacyAsked by rememberSaveable { mutableStateOf(CredentialsManager(context).wasPrivacyAsked()) }
            if (!privacyAsked) com.ksjd.testem.ui.screens.PrivacyDialog(onDone = { privacyAsked = true })
            else com.ksjd.testem.ui.screens.WhatsNewDialog()
            FloatingBar(
                tabs = tabs,
                selected = selected,
                anyAccount = appState.anyAccount,
                onSelect = { selected = it },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
            )
            val liveState by liveViewModel.state.collectAsState()
            liveState.trip?.let { trip ->
                com.ksjd.testem.ui.screens.TripSheet(
                    trip,
                    onDismiss = { liveViewModel.closeTrip(); viewModel.closedPlannerLeg() },
                    // From a planner result: getting off elsewhere edits the connection too (EM-0039).
                    onPickAlight = { order -> liveViewModel.setTripAlight(order); order?.let(viewModel::getOffAt) }
                )
            }
        }
    }
}

/**
 * The floating bottom bar: one pill in the theme's "bottom bar" colour (the rider can change it). The open tab
 * grows into a labelled button and the others shrink to icons, with a short animation, so it is always clear
 * where you are. Departures (times: a clock) and Planner (a bus) have clearly different icons.
 */
@Composable
private fun FloatingBar(tabs: List<AppTab>, selected: AppTab, anyAccount: Boolean, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    val colors = com.ksjd.testem.ui.theme.TransitTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            color = colors.bar,
            contentColor = colors.onBar,
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .padding(4.dp)
                    .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f)),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEach { tab ->
                    val on = selected == tab
                    val icon = when (tab) {
                        AppTab.Home -> Icons.Outlined.Home
                        AppTab.Departures -> Icons.Outlined.Schedule
                        AppTab.Planner -> Icons.Outlined.DirectionsBus
                        AppTab.History -> Icons.AutoMirrored.Outlined.ReceiptLong
                        AppTab.Account -> if (anyAccount) Icons.Outlined.AccountCircle else Icons.Outlined.Settings
                    }
                    val label = stringResource(
                        when {
                            tab == AppTab.Account && !anyAccount -> R.string.settings_title
                            else -> tab.labelRes
                        }
                    )
                    val fill by androidx.compose.animation.animateColorAsState(
                        if (on) colors.onBar.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent, label = "tab"
                    )
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .height(52.dp)
                            // Clipped first, so the tap ripple is a pill too (unclipped it flashed a square box).
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(fill)
                            .semantics(mergeDescendants = true) { this.selected = on }
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Tab,
                                onClickLabel = label
                            ) { if (!on) com.ksjd.testem.ui.components.Haptics.perform(view); onSelect(tab) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = if (on) null else label,
                            tint = if (on && tab == AppTab.Home && colors.onBar.luminance() > 0.5f) colors.live else colors.onBar)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = on,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally()
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
