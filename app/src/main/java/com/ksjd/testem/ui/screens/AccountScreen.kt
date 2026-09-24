package com.ksjd.testem.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.ScreenHeader
import com.ksjd.testem.ui.components.SectionLabel
import com.ksjd.testem.ui.components.daysLeftText
import com.ksjd.testem.ui.theme.TransitTheme

private enum class SettingsPage { Appearance, Security, Reminders, Language, Community, BugReport, Developer }

@Composable
fun AccountScreen(viewModel: AppViewModel) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val current = page
    if (current != null) {
        BackHandler { page = null }
        SettingsPageScreen(current, viewModel, onBack = { page = null })
        return
    }
    AccountOverview(viewModel, onOpen = { page = it })
}

@Composable
private fun AccountOverview(viewModel: AppViewModel, onOpen: (SettingsPage) -> Unit) {
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val card = ticket.selectedCard
    if (showSupport) SupportDialog { showSupport = false }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.logout_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = ticket.userName.ifBlank { stringResource(R.string.tab_account) },
            subtitle = app.email,
            actions = {
                IconButton(onClick = viewModel::refreshAccount, enabled = !ticket.isRefreshingAccount) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_button))
                }
            }
        )

        if (card != null) {
            CardImage(card)
            BalanceBlock(card)
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
        } else {
            Notice(stringResource(R.string.account_no_details), modifier = Modifier.padding(16.dp))
        }

        if (ticket.cards.size > 1) {
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

        SectionLabel(stringResource(R.string.settings_title))
        ListGroup {
            SettingsLink(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_hint)) { onOpen(SettingsPage.Reminders) }
            GroupDivider()
            SettingsLink(stringResource(R.string.security_title), stringResource(R.string.security_subtitle)) { onOpen(SettingsPage.Security) }
            GroupDivider()
            SettingsLink(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_hint)) { onOpen(SettingsPage.Appearance) }
            GroupDivider()
            SettingsLink(stringResource(R.string.language_title), languageName(app.languageCode)) { onOpen(SettingsPage.Language) }
            GroupDivider()
            SettingsLink(stringResource(R.string.settings_community), stringResource(R.string.settings_community_hint)) { onOpen(SettingsPage.Community) }
            GroupDivider()
            SettingsLink(stringResource(R.string.settings_developer), stringResource(R.string.settings_developer_hint)) { onOpen(SettingsPage.Developer) }
        }
        Spacer(Modifier.height(16.dp))
        ListGroup {
            SettingsLink(stringResource(R.string.bug_title), stringResource(R.string.bug_hint)) { onOpen(SettingsPage.BugReport) }
            GroupDivider()
            SettingsLink(stringResource(R.string.support_title), stringResource(R.string.support_hint)) { showSupport = true }
        }
        Spacer(Modifier.height(16.dp))
        ListGroup {
            ListRow(
                title = stringResource(R.string.logout),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { confirmSignOut = true }
            )
        }
    }
}

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

@Composable
private fun BalanceBlock(card: AccountDetails) {
    val balance = card.creditLastBalance ?: return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.account_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Format.money(balance, card.currencySymbol), style = MaterialTheme.typography.displayLarge)
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
        SettingsPage.BugReport -> stringResource(R.string.bug_title)
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
            SettingsPage.BugReport -> BugReportForm(viewModel, onDone = onBack)
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

    @Composable
    fun field(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
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

@Composable
private fun AppearanceSettings(app: AppState, viewModel: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var primary by rememberSaveable { mutableStateOf("") }
    var secondary by rememberSaveable { mutableStateOf("") }
    var accent by rememberSaveable { mutableStateOf("") }
    val p = parseColorHex(primary)
    val s = parseColorHex(secondary)
    val t = parseColorHex(accent)

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
        HexField(primary, stringResource(R.string.primary_hex_label), p) { primary = it }
        HexField(secondary, stringResource(R.string.secondary_hex_label), s) { secondary = it }
        HexField(accent, stringResource(R.string.tertiary_hex_label), t) { accent = it }
        Button(
            onClick = {
                viewModel.addThemePreset(name.trim(), p!!, s!!, t!!)
                name = ""; primary = ""; secondary = ""; accent = ""
            },
            enabled = name.isNotBlank() && p != null && s != null && t != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text(stringResource(R.string.save_preset)) }
    }
}

@Composable
private fun HexField(value: String, label: String, parsed: Long?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 9) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        placeholder = { Text("#1D4FB8") },
        trailingIcon = { if (parsed != null) Swatch(Color(parsed)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth()
    )
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

private fun parseColorHex(raw: String): Long? {
    val cleaned = raw.trim().removePrefix("#")
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    if (!normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return normalized.toLongOrNull(16)
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
