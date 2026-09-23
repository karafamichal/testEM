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
import androidx.compose.material.icons.outlined.QrCode2
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ksjd.testem.ui.screens.LoginScreen
import com.ksjd.testem.ui.screens.PinSetupScreen
import com.ksjd.testem.ui.screens.PinUnlockScreen
import com.ksjd.testem.ui.screens.PlannerScreen
import com.ksjd.testem.ui.screens.TicketScreen
import com.ksjd.testem.ui.theme.TestEMTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the saved language before any UI is inflated.
        val languageCode = CredentialsManager(this).getLanguageCode()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != languageCode) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode))
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
}

enum class AppTab(val labelRes: Int, val icon: ImageVector) {
    Ticket(R.string.tab_ticket, Icons.Outlined.QrCode2),
    Departures(R.string.tab_departures, Icons.Outlined.DirectionsBus),
    Planner(R.string.tab_planner, Icons.AutoMirrored.Outlined.AltRoute),
    History(R.string.tab_history, Icons.AutoMirrored.Outlined.ReceiptLong),
    Account(R.string.tab_account, Icons.Outlined.AccountCircle)
}

@Composable
fun AppRoot(viewModel: AppViewModel, liveViewModel: LiveViewModel, activity: FragmentActivity) {
    val appState by viewModel.appState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var browsingAsGuest by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(appState.isAppUnlocked, appState.isLoggedIn, appState.isLoading, appState.email) {
        if (appState.isAppUnlocked && !appState.isLoggedIn && !appState.isLoading &&
            appState.loginError.isBlank() && viewModel.hasSavedCredentials()
        ) {
            viewModel.loginWithSavedCredentials()
        }
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

    val browse = { browsingAsGuest = true }
    when {
        browsingAsGuest && !appState.isLoggedIn -> MainScaffold(
            viewModel = viewModel,
            liveViewModel = liveViewModel,
            activity = activity,
            guest = true,
            onExitGuest = { browsingAsGuest = false }
        )
        !appState.isPinSet -> PinSetupScreen(viewModel, onBrowse = browse)
        !appState.isAppUnlocked -> PinUnlockScreen(viewModel, appState, activity, onBrowse = browse)
        appState.isLoggedIn -> MainScaffold(viewModel, liveViewModel, activity, guest = false, onExitGuest = {})
        else -> LoginScreen(viewModel, appState, activity, onBrowse = browse)
    }
}

@Composable
private fun MainScaffold(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    activity: FragmentActivity,
    guest: Boolean,
    onExitGuest: () -> Unit
) {
    val tabs = if (guest) listOf(AppTab.Departures, AppTab.Planner) else AppTab.entries
    var selected by rememberSaveable { mutableStateOf(tabs.first()) }
    if (selected !in tabs) selected = tabs.first()

    BackHandler(enabled = selected != tabs.first() || guest) {
        if (selected != tabs.first()) selected = tabs.first() else onExitGuest()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes), maxLines = 1) },
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
                AppTab.Ticket -> TicketScreen(
                    viewModel = viewModel,
                    liveViewModel = liveViewModel,
                    activity = activity,
                    onOpenDepartures = { selected = AppTab.Departures },
                    onOpenAccount = { selected = AppTab.Account }
                )
                AppTab.Departures -> DeparturesScreen(
                    liveViewModel = liveViewModel,
                    onExitGuest = if (guest) onExitGuest else null
                )
                AppTab.Planner -> PlannerScreen(
                    viewModel = viewModel,
                    onOpenStop = openStopFromPlanner,
                    onExitGuest = if (guest) onExitGuest else null
                )
                AppTab.History -> HistoryScreen(viewModel = viewModel)
                AppTab.Account -> AccountScreen(viewModel = viewModel)
            }
        }
    }
}
