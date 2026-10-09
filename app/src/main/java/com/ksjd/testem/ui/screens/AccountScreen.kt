package com.ksjd.testem.ui.screens

import androidx.compose.material.icons.outlined.QrCode2
import com.ksjd.testem.ui.components.NoticeTone
import androidx.compose.material3.CircularProgressIndicator
import com.ksjd.testem.ui.components.WalletSwitch
import com.ksjd.testem.IdsState
import com.ksjd.testem.Wallet
import androidx.compose.material3.FilledTonalButton
import android.content.Intent
import android.net.Uri
import com.ksjd.testem.CredentialsManager

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ksjd.testem.AccountDetails
import com.ksjd.testem.AppState
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.BuildConfig
import com.ksjd.testem.R
import com.ksjd.testem.TicketPhase
import com.ksjd.testem.reminders.Reminders
import com.ksjd.testem.ui.Format
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.ColorPickDialog
import com.ksjd.testem.ui.components.ColorSwatch
import com.ksjd.testem.ui.components.CustomColorSwatch
import com.ksjd.testem.ui.components.hexOf
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.components.daysLeftText
import com.ksjd.testem.ui.theme.TransitTheme

private enum class SettingsPage { Appearance, Security, Reminders, Language, Community, Watch, BugReport, Feature, Developer }

@Composable
fun AccountScreen(viewModel: AppViewModel, onUnlock: () -> Unit, onSignIn: (Wallet) -> Unit) {
    val app by viewModel.appState.collectAsState()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // "Report a problem" on the watch sent its logs: the form, with them attached.
    val reportRequested by com.ksjd.testem.hub.AppLogs.reportRequested.collectAsState()
    LaunchedEffect(reportRequested) {
        if (reportRequested) { page = SettingsPage.BugReport; com.ksjd.testem.hub.AppLogs.reportRequested.value = false }
    }
    val current = page
    // Pages behind the PIN close when the app locks.
    if (current != null && current in LOCKED_PAGES && !(app.anyAccount && app.isAppUnlocked)) page = null
    if (current != null && page != null) {
        BackHandler { page = null }
        SettingsPageScreen(current, viewModel, onBack = { page = null })
        return
    }
    AccountOverview(viewModel, onUnlock, onSignIn, onOpen = { page = it })
}

/** Security (PIN) and Developer (raw token) need the account unlocked. */
private val LOCKED_PAGES = setOf(SettingsPage.Security, SettingsPage.Developer)

@Composable
private fun AccountOverview(viewModel: AppViewModel, onUnlock: () -> Unit, onSignIn: (Wallet) -> Unit, onOpen: (SettingsPage) -> Unit) {
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val ids by viewModel.idsState.collectAsState()
    val unlocked = app.anyAccount && app.isAppUnlocked
    // Which account the page shows (the selector on top when both are saved, EM-0023).
    var picked by rememberSaveable { mutableStateOf(Wallet.SADZV) }
    val shown = when {
        app.bothAccounts -> picked
        app.hasIdsAccount -> Wallet.IDS
        else -> Wallet.SADZV
    }
    val card = ticket.selectedCard.takeIf { unlocked && app.hasAccount && shown == Wallet.SADZV }
    var askPrimary by remember { mutableStateOf(false) }
    if (askPrimary) PrimaryEmailDialog(app, onDismiss = { askPrimary = false }) { viewModel.setPrimaryAccount(it); askPrimary = false }
    if (showSupport) SupportDialog { showSupport = false }
    var topUp by remember { mutableStateOf(false) }
    if (topUp && card != null) TopUpSheet(viewModel, card) { topUp = false }
    // "Add credit" on the low-credit notice or alert: the top-up, once the card is shown (after unlocking).
    val topUpRequested by com.ksjd.testem.reminders.Reminders.topUpRequested.collectAsState()
    LaunchedEffect(topUpRequested, card != null) {
        if (topUpRequested && card != null) { topUp = true; com.ksjd.testem.reminders.Reminders.topUpRequested.value = false }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.account_remove_which, walletName(shown))) },
            text = { Text(stringResource(R.string.account_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    if (shown == Wallet.IDS) viewModel.removeIdsAccount() else viewModel.removeAccount()
                }) { Text(stringResource(R.string.account_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = when {
                unlocked && shown == Wallet.SADZV -> ticket.userName.ifBlank { stringResource(R.string.tab_account) }
                unlocked -> ids.account?.name?.ifBlank { null } ?: walletName(Wallet.IDS)
                app.anyAccount -> stringResource(R.string.tab_account)
                else -> stringResource(R.string.settings_title)
            },
            subtitle = (if (shown == Wallet.IDS) app.idsEmail else app.email).takeIf { unlocked },
            actions = {
                if (unlocked) {
                    IconButton(
                        onClick = { if (shown == Wallet.IDS) viewModel.refreshIds() else viewModel.refreshAccount() },
                        enabled = !ticket.isRefreshingAccount && !ids.isLoading
                    ) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_button))
                    }
                }
            }
        )
        if (unlocked && app.bothAccounts) WalletSwitch(shown, { picked = it ?: Wallet.SADZV }, modifier = Modifier.padding(bottom = 4.dp))

        when {
            !app.anyAccount -> {
                SectionLabel(stringResource(R.string.account_section))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WalletRow(Wallet.SADZV, null) { onSignIn(Wallet.SADZV) }
                    WalletRow(Wallet.IDS, null) { onSignIn(Wallet.IDS) }
                }
            }
            !unlocked -> Notice(
                stringResource(R.string.account_locked),
                actionLabel = stringResource(R.string.unlock_button),
                onAction = onUnlock,
                modifier = Modifier.padding(16.dp)
            )
            shown == Wallet.IDS -> IdsAccountDetails(ids)
            card != null -> {
                CardImage(card)
                BalanceBlock(card, onTopUp = { topUp = true })
                SectionLabel(stringResource(R.string.account_validity))
                ListGroup {
                    ValidityRow(stringResource(R.string.account_ticket), card.ticketValidFrom, card.ticketValidTo)
                    GroupDivider()
                    ValidityRow(stringResource(R.string.account_card), card.cardValidFrom, card.cardValidTo)
                    if (card.discountValidTo > 0) {
                        GroupDivider()
                        ValidityRow(stringResource(R.string.account_discount), card.discountValidFrom, card.discountValidTo)
                    }
                }
            }
            else -> Notice(stringResource(R.string.account_no_details), modifier = Modifier.padding(16.dp))
        }

        if (card != null && ticket.cards.size > 1) {
            SectionLabel(stringResource(R.string.account_cards))
            ListGroup {
                ticket.cards.forEachIndexed { index, c ->
                    if (index > 0) GroupDivider()
                    ListRow(
                        title = cardLabel(c),
                        subtitle = listOfNotNull(
                            c.organizationName.takeIf { it.isNotBlank() },
                            c.creditLastBalance?.let { Format.money(it, c.currencySymbol) }
                        ).joinToString(", "),
                        onClick = { viewModel.selectCard(c.snr) },
                        trailing = { RadioButton(selected = c.snr == ticket.selectedSnr, onClick = { viewModel.selectCard(c.snr) }) }
                    )
                }
            }
        }

        if (card != null) {
            SectionLabel(stringResource(R.string.account_details))
            ListGroup {
                if (card.organizationName.isNotBlank()) {
                    ListRow(stringResource(R.string.account_organization), subtitle = card.organizationName)
                    GroupDivider()
                }
                ListRow(stringResource(R.string.account_card_number), subtitle = card.snr)
            }
        }

        // The account not added yet, so it can be added from here (EM-0023).
        if (unlocked && !app.bothAccounts) {
            SectionLabel(stringResource(R.string.account_add_other))
            val missing = if (app.hasAccount) Wallet.IDS else Wallet.SADZV
            Box(Modifier.padding(horizontal = 16.dp)) { WalletRow(missing, null) { onSignIn(missing) } }
        }
        if (unlocked && app.bothAccounts) {
            SectionLabel(stringResource(R.string.tickets_settings))
            ListGroup {
                ListRow(
                    stringResource(R.string.ticket_auto_switch),
                    subtitle = stringResource(R.string.ticket_auto_switch_hint),
                    onClick = { viewModel.setTicketAuto(!app.ticketAuto) },
                    trailing = { Switch(app.ticketAuto, viewModel::setTicketAuto) }
                )
                GroupDivider()
                ListRow(
                    stringResource(R.string.ticket_default),
                    subtitle = walletName(app.ticketDefault),
                    onClick = { viewModel.setTicketDefault(if (app.ticketDefault == Wallet.IDS) Wallet.SADZV else Wallet.IDS) },
                    trailing = { Text(stringResource(R.string.ticket_default_change), color = MaterialTheme.colorScheme.primary) }
                )
                if (!app.email.trim().equals(app.idsEmail.trim(), ignoreCase = true)) {
                    GroupDivider()
                    ListRow(stringResource(R.string.primary_setting), subtitle = app.contactEmail, onClick = { askPrimary = true })
                }
            }
        }

        SectionLabel(stringResource(R.string.settings_title))
        ListGroup {
            SettingsLink(stringResource(R.string.settings_reminders), stringResource(if (app.hasAccount) R.string.settings_reminders_hint else R.string.settings_reminders_hint_guest)) { onOpen(SettingsPage.Reminders) }
            if (app.anyAccount) {
                GroupDivider()
                SettingsLink(stringResource(R.string.security_title), stringResource(R.string.security_subtitle)) {
                    if (unlocked) onOpen(SettingsPage.Security) else onUnlock()
                }
            }
            GroupDivider()
            SettingsLink(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_hint)) { onOpen(SettingsPage.Appearance) }
            GroupDivider()
            SettingsLink(stringResource(R.string.language_title), languageName(app.languageCode)) { onOpen(SettingsPage.Language) }
            GroupDivider()
            SettingsLink(stringResource(R.string.settings_community), stringResource(R.string.settings_community_hint)) { onOpen(SettingsPage.Community) }
            // Only with the Plan&Ride watch app on a paired watch: without one the page has nothing to set.
            val context = androidx.compose.ui.platform.LocalContext.current
            val hasWatch by androidx.compose.runtime.produceState(com.ksjd.testem.live.WatchSync.watchApp) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ksjd.testem.live.WatchSync.checkWatch(context) }
            }
            if (hasWatch) {
                GroupDivider()
                SettingsLink(stringResource(R.string.settings_watch), stringResource(R.string.settings_watch_hint)) { onOpen(SettingsPage.Watch) }
            }
            if (app.hasAccount) {
                GroupDivider()
                SettingsLink(stringResource(R.string.settings_developer), stringResource(R.string.settings_developer_hint)) {
                    if (unlocked) onOpen(SettingsPage.Developer) else onUnlock()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ListGroup {
            val context = LocalContext.current
            val prefs = remember { CredentialsManager(context) }
            var whatsNew by remember { mutableStateOf(prefs.getWhatsNewEnabled()) }
            val setWhatsNew: (Boolean) -> Unit = { on -> whatsNew = on; prefs.saveWhatsNewEnabled(on) }
            ListRow(
                stringResource(R.string.whats_new_switch),
                subtitle = stringResource(R.string.whats_new_switch_hint),
                onClick = { setWhatsNew(!whatsNew) },
                trailing = { Switch(whatsNew, setWhatsNew) }
            )
            GroupDivider()
            var change by remember { mutableStateOf(prefs.getChangeMinutes()) }
            var pickChange by remember { mutableStateOf(false) }
            val changeLabel: (Int) -> String = { m ->
                if (m == 0) context.getString(R.string.change_time_any) else context.getString(R.string.change_time_minutes, m)
            }
            ListRow(stringResource(R.string.change_time), subtitle = changeLabel(change), onClick = { pickChange = true })
            if (pickChange) {
                AlertDialog(
                    onDismissRequest = { pickChange = false },
                    title = { Text(stringResource(R.string.change_time)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.change_time_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(0, 1, 2, 3, 5, 10).forEach { m ->
                                ListRow(changeLabel(m), onClick = {
                                    change = m; prefs.saveChangeMinutes(m); com.ksjd.testem.TimetableService.changeMinutes = m; pickChange = false
                                }, trailing = { RadioButton(selected = change == m, onClick = null) })
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { pickChange = false }) { Text(stringResource(R.string.cancel)) } }
                )
            }
            GroupDivider()
            var haptics by remember { mutableStateOf(prefs.getHapticsEnabled()) }
            val setHaptics: (Boolean) -> Unit = { on ->
                haptics = on
                prefs.saveHapticsEnabled(on)
                com.ksjd.testem.ui.components.Haptics.enabled = on
            }
            ListRow(
                stringResource(R.string.haptics_switch),
                subtitle = stringResource(R.string.haptics_switch_hint),
                onClick = { setHaptics(!haptics) },
                trailing = { Switch(haptics, setHaptics) }
            )
            GroupDivider()
            // Firebase remembers this itself; the switch only reads and changes it.
            var stats by remember { mutableStateOf(prefs.getAnalyticsEnabled()) }
            val setStats: (Boolean) -> Unit = { on ->
                stats = on
                prefs.saveAnalyticsEnabled(on)
                com.google.firebase.analytics.FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(on)
            }
            ListRow(
                stringResource(R.string.analytics_switch),
                subtitle = stringResource(R.string.analytics_switch_hint),
                onClick = { setStats(!stats) },
                trailing = { Switch(stats, setStats) }
            )
            GroupDivider()
            SettingsLink(stringResource(R.string.bug_title), stringResource(R.string.bug_hint)) { onOpen(SettingsPage.BugReport) }
            GroupDivider()
            SettingsLink(stringResource(R.string.feature_title), stringResource(R.string.feature_hint)) { onOpen(SettingsPage.Feature) }
            GroupDivider()
            SettingsLink(stringResource(R.string.support_title), stringResource(R.string.support_hint)) { showSupport = true }
            GroupDivider()
            SettingsLink(stringResource(R.string.privacy_policy_title), stringResource(R.string.privacy_policy_hint)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl(context, "privacy")))) }
            }
        }
        if (app.anyAccount) {
            Spacer(Modifier.height(16.dp))
            ListGroup {
                if (unlocked && app.isPinSet) {
                    ListRow(title = stringResource(R.string.lock_now), onClick = viewModel::lock)
                    GroupDivider()
                }
                ListRow(
                    title = stringResource(R.string.account_remove_which, walletName(shown)),
                    subtitle = stringResource(R.string.account_remove_hint),
                    titleColor = MaterialTheme.colorScheme.error,
                    // Removing needs the PIN too, so someone holding the phone can't wipe it.
                    onClick = { if (unlocked) confirmRemove = true else onUnlock() }
                )
            }
        }
    }
}

/** Opt-in: what changed in this version, once after an update, with a link to the full changelog. */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    val prefs = remember { CredentialsManager(context) }
    var show by remember { mutableStateOf(prefs.getWhatsNewEnabled() && prefs.getWhatsNewSeenVersion() < BuildConfig.VERSION_CODE) }
    if (!show) return
    val close = { prefs.saveWhatsNewSeenVersion(BuildConfig.VERSION_CODE); show = false }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = { Text(stringResource(R.string.whats_new_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.whats_new_ok)) } },
        dismissButton = {
            TextButton(onClick = {
                close()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl(context, "changelog")))) }
            }) { Text(stringResource(R.string.whats_new_changelog)) }
        }
    )
}

/** The Plan&Ride website (served by emhub): Slovak at /page, English at /en/page. */
private fun siteUrl(context: android.content.Context, page: String): String =
    "https://par.karafa.net/" + (if (CredentialsManager(context).getLanguageCode() == "en") "en/" else "") + page

/** A short note, then the support page in the browser. */
@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_title)) },
        text = { Text(stringResource(R.string.support_body)) },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SUPPORT_URL)))
                }
            }) { Text(stringResource(R.string.support_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_later)) } }
    )
}

private const val SUPPORT_URL = "https://karafa.net/support/"

@Composable
private fun CardImage(card: AccountDetails) {
    val bitmap = remember(card.cardTemplateBase64) {
        runCatching {
            val cleaned = card.cardTemplateBase64.replace(Regex("[^A-Za-z0-9+/=_-]"), "")
            if (cleaned.isBlank()) return@runCatching null
            val flags = if (cleaned.contains('-') || cleaned.contains('_')) Base64.URL_SAFE else Base64.DEFAULT
            val bytes = Base64.decode(cleaned + "=".repeat((4 - cleaned.length % 4) % 4), flags)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.account_card_image),
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
    )
}

/** The IDS BBSK account: credit, tickets and identifiers as the e-shop has them (EM-0023; top-up stays in the e-shop). */
@Composable
private fun IdsAccountDetails(ids: IdsState) {
    val account = ids.account
    if (account == null) {
        if (ids.isLoading) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        else Notice(stringResource(if (ids.signedOut) R.string.ids_signed_out else R.string.account_no_details), modifier = Modifier.padding(16.dp))
        return
    }
    val context = LocalContext.current
    if (ids.signedOut) Notice(stringResource(R.string.ids_signed_out), tone = NoticeTone.Error, modifier = Modifier.padding(16.dp))
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.account_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(account.credit?.let { Format.money(it, "€") } ?: "–", style = MaterialTheme.typography.displayLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://eshop.idsbbsk.sk/praca-s-kreditom"))) }
            }) { Text(stringResource(R.string.ids_open_shop)) }
        }
        if (account.creditNote.isNotBlank()) {
            Text(account.creditNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val now = System.currentTimeMillis()
    val valid = account.tickets.filter { it.valid && (it.validToMs == 0L || it.validToMs > now) }
    SectionLabel(stringResource(R.string.account_validity))
    ListGroup {
        if (valid.isEmpty()) ListRow(stringResource(R.string.ids_no_ticket))
        valid.forEachIndexed { index, t ->
            if (index > 0) GroupDivider()
            val days = Format.daysUntil(t.validToMs)
            ListRow(
                title = t.name,
                subtitle = stringResource(R.string.account_valid_range, Format.date(t.validFromMs), Format.date(t.validToMs)) + "\n" + t.identifier,
                trailing = { Text(daysLeftText(days), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }
    SectionLabel(stringResource(R.string.ids_identifiers))
    ListGroup {
        account.identifiers.forEachIndexed { index, id ->
            if (index > 0) GroupDivider()
            ListRow(
                title = id.alias.ifBlank { stringResource(R.string.ids_identifier_number, id.number) },
                subtitle = listOf(id.cardNumber, id.products.joinToString(", ")).filter { it.isNotBlank() }.joinToString("\n"),
                trailing = { if (id.qrId != null) Icon(Icons.Outlined.QrCode2, stringResource(R.string.qr_code_content_desc)) }
            )
        }
    }
}

@Composable
private fun BalanceBlock(card: AccountDetails, onTopUp: () -> Unit) {
    val balance = card.creditLastBalance ?: return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.account_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Format.money(balance, card.currencySymbol), style = MaterialTheme.typography.displayLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onTopUp) { Text(stringResource(R.string.topup_button)) }
        }
        if (card.cardTypeName.isNotBlank()) {
            Text(card.cardTypeName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ValidityRow(label: String, from: Long, to: Long) {
    val days = Format.daysUntil(to)
    val color = when {
        to <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        days < 0 -> MaterialTheme.colorScheme.error
        days <= 7 -> TransitTheme.colors.late
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListRow(
        title = label,
        subtitle = if (to > 0) stringResource(R.string.account_valid_range, Format.date(from), Format.date(to)) else stringResource(R.string.account_not_set),
        trailing = {
            if (to > 0) Text(daysLeftText(days), style = MaterialTheme.typography.labelLarge, color = color)
        }
    )
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    )
}

@Composable
private fun languageName(code: String): String =
    if (code == "en") stringResource(R.string.language_english) else stringResource(R.string.language_slovak)

// --------------------------------------------------------------------------
// Settings pages
// --------------------------------------------------------------------------

@Composable
private fun SettingsPageScreen(page: SettingsPage, viewModel: AppViewModel, onBack: () -> Unit) {
    val app by viewModel.appState.collectAsState()
    val title = when (page) {
        SettingsPage.Appearance -> stringResource(R.string.settings_appearance)
        SettingsPage.Security -> stringResource(R.string.security_title)
        SettingsPage.Reminders -> stringResource(R.string.settings_reminders)
        SettingsPage.Language -> stringResource(R.string.language_title)
        SettingsPage.Community -> stringResource(R.string.settings_community)
        SettingsPage.Watch -> stringResource(R.string.settings_watch)
        SettingsPage.BugReport -> stringResource(R.string.bug_title)
        SettingsPage.Feature -> stringResource(R.string.feature_title)
        SettingsPage.Developer -> stringResource(R.string.settings_developer)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        when (page) {
            SettingsPage.Appearance -> AppearanceSettings(app, viewModel)
            SettingsPage.Security -> SecuritySettings(app, viewModel)
            SettingsPage.Reminders -> ReminderSettings(app, viewModel)
            SettingsPage.Language -> LanguageSettings(app, viewModel)
            SettingsPage.Community -> CommunitySettings()
            SettingsPage.Watch -> WatchSettings()
            SettingsPage.BugReport -> BugReportForm(viewModel, onDone = onBack)
            SettingsPage.Feature -> BugReportForm(viewModel, onDone = onBack, feature = true)
            SettingsPage.Developer -> DeveloperSettings(viewModel)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

@Composable
private fun ReminderSettings(app: AppState, viewModel: AppViewModel) {
    val context = LocalContext.current
    var pendingEnable by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var permissionDenied by remember { mutableStateOf(!Reminders.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) pendingEnable?.invoke(true)
        pendingEnable = null
    }
    val enable: (Boolean, (Boolean) -> Unit) -> Unit = { on, setter ->
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.canNotify(context)) {
            pendingEnable = setter
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setter(on)
        }
    }

    var thresholdText by rememberSaveable { mutableStateOf("%.2f".format(app.lowCreditWarningThreshold)) }

    // Credit and expiry come from the account; without one only the bus alerts apply.
    if (!app.hasAccount) {
        ArrivalAlertSetting()
        return
    }
    Text(
        stringResource(R.string.reminders_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    if (permissionDenied && (app.lowCreditAlerts || app.expiryAlerts)) {
        Notice(stringResource(R.string.reminders_permission_needed), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    ListGroup {
        SwitchRow(
            stringResource(R.string.reminders_low_credit),
            stringResource(R.string.reminders_low_credit_hint, Format.money(app.lowCreditWarningThreshold)),
            app.lowCreditAlerts
        ) { enable(it, viewModel::setLowCreditAlerts) }
        GroupDivider()
        SwitchRow(
            stringResource(R.string.reminders_expiry),
            stringResource(R.string.reminders_expiry_hint),
            app.expiryAlerts
        ) { enable(it, viewModel::setExpiryAlerts) }
    }
    ArrivalAlertSetting()
    SectionLabel(stringResource(R.string.low_credit_threshold_title))
    OutlinedTextField(
        value = thresholdText,
        onValueChange = { raw ->
            val normalized = raw.replace(',', '.')
            if (raw.length <= 7 && normalized.matches(Regex("^[0-9]*([.][0-9]{0,2})?$"))) {
                thresholdText = raw
                normalized.toDoubleOrNull()?.let(viewModel::setLowCreditWarningThreshold)
            }
        },
        label = { Text(stringResource(R.string.low_credit_threshold_label)) },
        supportingText = { Text(stringResource(R.string.low_credit_threshold_description)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
}

@Composable
private fun SecuritySettings(app: AppState, viewModel: AppViewModel) {
    var showChangePin by remember { mutableStateOf(false) }
    if (showChangePin) ChangePinDialog(viewModel) { showChangePin = false }
    val timeouts = listOf(
        0 to stringResource(R.string.lock_timeout_immediately),
        30 to stringResource(R.string.lock_timeout_30_seconds),
        60 to stringResource(R.string.lock_timeout_1_minute),
        300 to stringResource(R.string.lock_timeout_5_minutes)
    )
    SectionLabel(stringResource(R.string.lock_timeout_title))
    ListGroup {
        timeouts.forEachIndexed { index, (seconds, label) ->
            if (index > 0) GroupDivider()
            ListRow(
                title = label,
                onClick = { viewModel.setLockTimeoutSeconds(seconds) },
                trailing = { RadioButton(selected = app.lockTimeoutSeconds == seconds, onClick = { viewModel.setLockTimeoutSeconds(seconds) }) }
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    ListGroup {
        SwitchRow(stringResource(R.string.biometrics_label), stringResource(R.string.biometrics_hint), app.biometricEnabled, viewModel::setBiometricEnabled)
        GroupDivider()
        ListRow(stringResource(R.string.change_pin_button), onClick = { showChangePin = true })
    }
}

@Composable
private fun ChangePinDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val enterCurrent = stringResource(R.string.change_pin_error_enter_current)
    val tooShort = stringResource(R.string.change_pin_error_new_short)
    val mismatch = stringResource(R.string.change_pin_error_mismatch)
    val incorrect = stringResource(R.string.change_pin_error_incorrect)

    var show by remember { mutableStateOf(false) }
    @Composable
    fun field(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { RevealButton(show) { show = !show } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(current, stringResource(R.string.current_pin_label)) { current = it }
                field(next, stringResource(R.string.new_pin_label)) { next = it }
                field(confirm, stringResource(R.string.confirm_new_pin_label)) { confirm = it }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    current.length < 4 -> enterCurrent
                    next.length < 4 -> tooShort
                    next != confirm -> mismatch
                    !viewModel.changePin(current, next) -> incorrect
                    else -> ""
                }
                if (error.isEmpty()) onDismiss()
            }) { Text(stringResource(R.string.update_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** EM-0042: ready background colours (light and dark ones); any other by its hex code. */
private val BackgroundChoices = listOf(0xFFF3EEE4, 0xFFE8F0E6, 0xFFE6EEF7, 0xFFF6E8EC, 0xFF1C2433, 0xFF1E2A22, 0xFF2B1F2E, 0xFF000000)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(app: AppState, viewModel: AppViewModel) {
    // Which colour the picker is choosing: "bg", or the new theme's "p", "s", "t".
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var primary by rememberSaveable { mutableStateOf<Long?>(null) }
    var secondary by rememberSaveable { mutableStateOf<Long?>(null) }
    var accent by rememberSaveable { mutableStateOf<Long?>(null) }
    picking?.let { which ->
        ColorPickDialog(
            initial = when (which) { "bg" -> app.backgroundColor; "p" -> primary; "s" -> secondary; else -> accent },
            title = stringResource(when (which) {
                "bg" -> R.string.background_title; "p" -> R.string.primary_hex_label; "s" -> R.string.secondary_hex_label; else -> R.string.tertiary_hex_label
            }),
            onPick = { c ->
                when (which) { "bg" -> viewModel.setBackgroundColor(c); "p" -> primary = c; "s" -> secondary = c; else -> accent = c }
                picking = null
            },
            onDismiss = { picking = null }
        )
    }

    // EM-0042: light, dark or as the phone; and the app's own background colour.
    SectionLabel(stringResource(R.string.theme_mode_title))
    val modes = listOf("system" to R.string.theme_mode_system, "light" to R.string.theme_mode_light, "dark" to R.string.theme_mode_dark)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = app.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                enabled = app.backgroundColor == null,
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                label = { Text(stringResource(label), maxLines = 1) }
            )
        }
    }
    SectionLabel(stringResource(R.string.background_title))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The ready colours, then any other one through the colour picker.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackgroundChoices.forEach { color -> ColorSwatch(Color(color), selected = app.backgroundColor == color) { viewModel.setBackgroundColor(color) } }
            CustomColorSwatch(app.backgroundColor?.takeIf { it !in BackgroundChoices }?.let { Color(it) }) { picking = "bg" }
        }
        TextButton(onClick = { viewModel.setBackgroundColor(null) }, enabled = app.backgroundColor != null) {
            Text(stringResource(R.string.background_reset))
        }
        Text(stringResource(R.string.background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionLabel(stringResource(R.string.theme_presets_title))
    ListGroup {
        app.themePresets.forEachIndexed { index, preset ->
            if (index > 0) GroupDivider()
            ListRow(
                title = preset.name,
                onClick = { viewModel.selectThemePreset(preset.id) },
                leading = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        listOf(preset.primary, preset.secondary, preset.tertiary).forEach { Swatch(Color(it)) }
                    }
                },
                trailing = { RadioButton(selected = preset.id == app.selectedThemeId, onClick = { viewModel.selectThemePreset(preset.id) }) }
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    ListGroup {
        SwitchRow(stringResource(R.string.amoled_mode_title), stringResource(R.string.amoled_mode_subtitle), app.amoledEnabled, viewModel::setAmoledEnabled)
    }

    SectionLabel(stringResource(R.string.create_preset_title))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.preset_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ColorRow(stringResource(R.string.primary_hex_label), primary) { picking = "p" }
        ColorRow(stringResource(R.string.secondary_hex_label), secondary) { picking = "s" }
        ColorRow(stringResource(R.string.tertiary_hex_label), accent) { picking = "t" }
        val p = primary; val s = secondary; val t = accent
        Button(
            onClick = {
                if (p != null && s != null && t != null) viewModel.addThemePreset(name.trim(), p, s, t)
                name = ""; primary = null; secondary = null; accent = null
            },
            enabled = name.isNotBlank() && p != null && s != null && t != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text(stringResource(R.string.save_preset)) }
    }
}

/** One colour of a new theme: its name and swatch; a tap opens the colour picker. */
@Composable
private fun ColorRow(label: String, color: Long?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (color != null) ColorSwatch(Color(color), size = 32, onClick = onClick) else CustomColorSwatch(null, size = 32, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(color?.let { hexOf(Color(it)) } ?: stringResource(R.string.color_choose), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(22.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun LanguageSettings(app: AppState, viewModel: AppViewModel) {
    Spacer(Modifier.height(8.dp))
    ListGroup {
        listOf("sk" to stringResource(R.string.language_slovak), "en" to stringResource(R.string.language_english))
            .forEachIndexed { index, (code, label) ->
                if (index > 0) GroupDivider()
                ListRow(
                    title = label,
                    onClick = { viewModel.setLanguageCode(code) },
                    trailing = { RadioButton(selected = app.languageCode == code, onClick = { viewModel.setLanguageCode(code) }) }
                )
            }
    }
}

@Composable
private fun DeveloperSettings(viewModel: AppViewModel) {
    val ticket by viewModel.ticketState.collectAsState()
    // Re-read polling state whenever the phase changes.
    var polling by remember { mutableStateOf(viewModel.isPolling) }
    LaunchedEffect(ticket.phase) { polling = viewModel.isPolling }

    Text(
        stringResource(R.string.developer_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    ListGroup {
        SwitchRow(stringResource(R.string.developer_polling), stringResource(R.string.developer_polling_hint), polling) { on ->
            if (on) viewModel.startPolling() else viewModel.stopPolling(TicketPhase.Paused)
            polling = on
        }
        GroupDivider()
        ListRow(stringResource(R.string.developer_phase), subtitle = ticket.phase.name)
        GroupDivider()
        ListRow(stringResource(R.string.developer_last_code), subtitle = Format.clockWithSeconds(ticket.lastUpdateTime))
    }
    SectionLabel(stringResource(R.string.token_info_title))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ticket.tokenHex.isEmpty()) {
                    Text(stringResource(R.string.token_waiting), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.token_hex_label, ticket.tokenHex), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.token_b64_label, ticket.tokenBase64), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.developer_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}
