package com.ksjd.testem

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != languageCode) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode))
        }
        com.ksjd.testem.hub.AppLogs.installCrashRecorder(applicationContext)
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
            TestEMTheme(themePreset = preset, amoledMode = appState.amoledEnabled) {
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
    // Sign-in form opened from Settings ("Add account") or the home tab ("Sign in again").
    var signingIn by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(appState.isAppUnlocked, appState.hasAccount, appState.isLoggedIn, appState.isLoading, appState.email) {
        if (appState.isAppUnlocked && appState.hasAccount && !appState.isLoggedIn && !appState.isLoading &&
            appState.loginError.isBlank() && viewModel.hasSavedCredentials()
        ) {
            viewModel.loginWithSavedCredentials()
        }
    }
    LaunchedEffect(appState.hasAccount) { if (appState.hasAccount) signingIn = false }

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
        // First run: add the SADZV account now, or skip and use the app without it.
        !appState.onboarded -> LoginScreen(viewModel, appState, welcome = true, onSkip = viewModel::skipOnboarding)
        // An account was just added: its ticket needs a PIN.
        appState.hasAccount && !appState.isPinSet -> PinSetupScreen(viewModel)
        signingIn -> {
            BackHandler { signingIn = false; viewModel.clearLoginError() }
            LoginScreen(viewModel, appState, welcome = false, onSkip = { signingIn = false; viewModel.clearLoginError() })
        }
        else -> MainScaffold(viewModel, liveViewModel, activity, onSignIn = { signingIn = true })
    }
}

@Composable
private fun MainScaffold(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    activity: FragmentActivity,
    onSignIn: () -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    // History exists only with an account; the last tab is Account or just Settings.
    val tabs = AppTab.entries.filter { it != AppTab.History || appState.hasAccount }
    var selected by rememberSaveable { mutableStateOf(AppTab.Home) }
    if (selected !in tabs) selected = AppTab.Home
    var unlocking by remember { mutableStateOf(false) }
    val requestUnlock = { unlocking = true }
    if (unlocking) UnlockDialog(viewModel, appState, activity, onDismiss = { unlocking = false })

    BackHandler(enabled = selected != AppTab.Home) { selected = AppTab.Home }

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
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    val icon = when (tab) {
                        AppTab.Home -> if (appState.hasAccount) Icons.Outlined.QrCode2 else Icons.Outlined.Home
                        AppTab.Departures -> Icons.Outlined.DirectionsBus
                        AppTab.Planner -> Icons.AutoMirrored.Outlined.AltRoute
                        AppTab.History -> Icons.AutoMirrored.Outlined.ReceiptLong
                        AppTab.Account -> if (appState.hasAccount) Icons.Outlined.AccountCircle else Icons.Outlined.Settings
                    }
                    val label = if (tab == AppTab.Account && !appState.hasAccount) R.string.settings_title else tab.labelRes
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
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
            val liveState by liveViewModel.state.collectAsState()
            liveState.trip?.let { trip -> com.ksjd.testem.ui.screens.TripSheet(trip, onDismiss = liveViewModel::closeTrip, onPickAlight = liveViewModel::setTripAlight) }
        }
    }
}
