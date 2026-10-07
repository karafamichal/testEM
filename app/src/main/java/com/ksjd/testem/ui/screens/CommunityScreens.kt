package com.ksjd.testem.ui.screens

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.COMMUNITY_AUTO
import com.ksjd.testem.COMMUNITY_BUTTONS
import com.ksjd.testem.COMMUNITY_OFF
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.R
import com.ksjd.testem.hub.AppLogs
import com.ksjd.testem.hub.BugReport
import com.ksjd.testem.hub.HubClient
import com.ksjd.testem.live.Locations
import com.ksjd.testem.live.TripTracking
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.Haptics
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone
import com.ksjd.testem.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Location permission for the catch estimate: precise if the user allows it. */
@Composable
private fun rememberLocationRequest(onResult: (Boolean) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result.values.any { it })
    }

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Asked once on first run. Both features start off; nothing is sent until the user says yes. */
@Composable
fun PrivacyDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { CredentialsManager(context) }
    var mode by remember { mutableStateOf(prefs.getCommunityMode()) }
    var catch by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TripTracking.refresh(context)
        onDone()
    }
    // Automatic works with the app closed only with "Allow all the time": ask right away, not later in Settings.
    val askBackground = {
        if (mode == COMMUNITY_AUTO && Locations.hasPrecise(context) && !Locations.hasBackground(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) disclosure = true else onDone()
    }
    val location = rememberLocationRequest { granted ->
        if (!Locations.hasPrecise(context)) {
            if (!granted) prefs.saveCatchEnabled(false)
            if (prefs.getCommunityMode() == COMMUNITY_AUTO) prefs.saveCommunityMode(COMMUNITY_BUTTONS)
        }
        askBackground()
    }
    val finish = {
        prefs.saveCommunityMode(mode)
        prefs.saveCatchEnabled(catch)
        prefs.markPrivacyAsked()
        if ((catch || mode == COMMUNITY_AUTO) && !Locations.hasPrecise(context)) location.launch(LOCATION_PERMISSIONS) else askBackground()
    }
    if (disclosure) {
        BackgroundDisclosure(
            onContinue = { disclosure = false; background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            onNotNow = { disclosure = false; onDone() }
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text(stringResource(R.string.privacy_community_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.privacy_community_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ModeChoices(mode) { mode = it }
                }
                OptIn(stringResource(R.string.privacy_catch_title), stringResource(R.string.privacy_catch_body), catch) { catch = it }
                Text(stringResource(R.string.privacy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = finish) { Text(stringResource(R.string.privacy_done)) } }
    )
}

@Composable
private fun OptIn(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(start = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun CommunitySettings() {
    val context = LocalContext.current
    val prefs = remember { CredentialsManager(context) }
    var mode by remember { mutableStateOf(prefs.getCommunityMode()) }
    var catch by remember { mutableStateOf(prefs.getCatchEnabled()) }
    var timetable by remember { mutableStateOf(prefs.getCatchTimetableAlerts()) }
    var hasLocation by remember { mutableStateOf(Locations.hasPrecise(context)) }
    var pendingAuto by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
    val location = rememberLocationRequest { granted ->
        hasLocation = Locations.hasPrecise(context)
        if (pendingAuto) {
            pendingAuto = false
            if (hasLocation) { mode = COMMUNITY_AUTO; prefs.saveCommunityMode(COMMUNITY_AUTO) }
            if (hasLocation && !Locations.hasBackground(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) disclosure = true
        } else {
            catch = granted
            prefs.saveCatchEnabled(granted)
        }
        TripTracking.refresh(context)
    }
    var hasBackground by remember { mutableStateOf(Locations.hasBackground(context)) }
    // Android 11+ opens the app's location settings page for this; the user picks "Allow all the time".
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasBackground = Locations.hasBackground(context)
        TripTracking.refresh(context)
    }
    val setMode: (String) -> Unit = { m ->
        if (m == COMMUNITY_AUTO && !Locations.hasPrecise(context)) { pendingAuto = true; location.launch(LOCATION_PERMISSIONS) }
        else {
            mode = m; prefs.saveCommunityMode(m); TripTracking.refresh(context)
            if (m == COMMUNITY_AUTO && !hasBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) disclosure = true
        }
    }

    Text(
        stringResource(R.string.privacy_community_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    ListGroup {
        listOf(
            Triple(COMMUNITY_OFF, R.string.community_mode_off, R.string.community_mode_off_hint),
            Triple(COMMUNITY_BUTTONS, R.string.community_mode_buttons, R.string.community_mode_buttons_hint),
            Triple(COMMUNITY_AUTO, R.string.community_mode_auto, R.string.community_mode_auto_hint)
        ).forEachIndexed { i, (value, title, hint) ->
            if (i > 0) GroupDivider()
            ListRow(
                stringResource(title),
                subtitle = stringResource(hint),
                onClick = { setMode(value) },
                trailing = { RadioButton(selected = mode == value, onClick = { setMode(value) }) }
            )
        }
    }
    if (mode == COMMUNITY_AUTO && hasLocation && !hasBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Notice(
            stringResource(R.string.location_background_needed),
            modifier = Modifier.padding(16.dp),
            tone = NoticeTone.Warning,
            actionLabel = stringResource(R.string.location_background_allow),
            onAction = { disclosure = true }
        )
    }
    // Google Play: a prominent disclosure must come before the "all the time" permission request.
    if (disclosure) {
        BackgroundDisclosure(
            onContinue = { disclosure = false; background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            onNotNow = { disclosure = false }
        )
    }
    var predict by remember { mutableStateOf(prefs.getPredictShift()) }
    Spacer(Modifier.height(8.dp))
    ListGroup {
        ListRow(stringResource(R.string.predict_switch), subtitle = stringResource(R.string.predict_switch_hint),
            onClick = { predict = !predict; prefs.savePredictShift(predict) },
            trailing = { Switch(predict, { predict = it; prefs.savePredictShift(it) }) })
    }
    Text(
        stringResource(R.string.privacy_catch_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    val setCatch: (Boolean) -> Unit = { on ->
        if (on && !Locations.hasPrecise(context)) location.launch(LOCATION_PERMISSIONS)
        else { catch = on; prefs.saveCatchEnabled(on); TripTracking.refresh(context) }
    }
    ListGroup {
        ListRow(stringResource(R.string.catch_switch), subtitle = stringResource(R.string.catch_switch_hint), onClick = { setCatch(!catch) },
            trailing = { Switch(catch, setCatch) })
        GroupDivider()
        ListRow(
            stringResource(R.string.catch_timetable_switch),
            subtitle = stringResource(R.string.catch_timetable_hint),
            onClick = if (catch) ({ timetable = !timetable; prefs.saveCatchTimetableAlerts(timetable) }) else null,
            trailing = { Switch(timetable, { timetable = it; prefs.saveCatchTimetableAlerts(it) }, enabled = catch) }
        )
    }
    if (catch && !hasLocation) {
        Notice(
            stringResource(R.string.catch_location_needed),
            modifier = Modifier.padding(16.dp),
            tone = NoticeTone.Warning,
            actionLabel = stringResource(R.string.catch_location_allow),
            onAction = { location.launch(LOCATION_PERMISSIONS) }
        )
    }
}

/** Google Play: this prominent disclosure must come right before the "Allow all the time" request. */
@Composable
private fun BackgroundDisclosure(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.location_background_title)) },
        text = { Text(stringResource(R.string.location_background_disclosure)) },
        confirmButton = { Button(onClick = onContinue) { Text(stringResource(R.string.location_background_continue)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.location_background_not_now)) } }
    )
}

/** Off / Buttons / Automatic, compact for the first-run dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChoices(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        listOf(COMMUNITY_OFF to R.string.community_mode_off, COMMUNITY_BUTTONS to R.string.community_mode_buttons, COMMUNITY_AUTO to R.string.community_mode_auto)
            .forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(stringResource(label)) },
                    leadingIcon = if (selected == value) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null
                )
            }
    }
    val hint = when (selected) {
        COMMUNITY_BUTTONS -> R.string.community_mode_buttons_hint
        COMMUNITY_AUTO -> R.string.community_mode_auto_hint
        else -> R.string.community_mode_off_hint
    }
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val ARRIVAL_CHOICES = listOf(1, 2, 3, 5, 10)

/** Reminders page: how early the "bus arrives" alert fires when following a bus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrivalAlertSetting() {
    val context = LocalContext.current
    val prefs = remember { CredentialsManager(context) }
    var minutes by remember { mutableStateOf(prefs.getArrivalAlertMinutes()) }
    var custom by rememberSaveable { mutableStateOf(minutes !in ARRIVAL_CHOICES) }
    var customText by rememberSaveable { mutableStateOf(minutes.toString()) }
    val set: (Int) -> Unit = { m -> minutes = m; prefs.saveArrivalAlertMinutes(m) }

    SectionLabel(stringResource(R.string.arrival_alert_title))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.arrival_alert_label), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ARRIVAL_CHOICES.forEach { m ->
                val on = !custom && minutes == m
                FilterChip(
                    selected = on,
                    onClick = { custom = false; set(m) },
                    label = { Text(stringResource(R.string.arrival_alert_minutes, m)) },
                    leadingIcon = if (on) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null
                )
            }
            FilterChip(selected = custom, onClick = { custom = true; customText = minutes.toString() },
                label = { Text(stringResource(R.string.arrival_alert_custom)) },
                leadingIcon = if (custom) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
        }
        if (custom) {
            val value = customText.toIntOrNull()
            OutlinedTextField(
                value = customText,
                onValueChange = { raw ->
                    if (raw.length <= 2 && raw.all(Char::isDigit)) {
                        customText = raw
                        raw.toIntOrNull()?.takeIf { it in 1..com.ksjd.testem.MAX_ARRIVAL_ALERT_MINUTES }?.let(set)
                    }
                },
                label = { Text(stringResource(R.string.arrival_alert_custom_label)) },
                isError = value == null || value !in 1..com.ksjd.testem.MAX_ARRIVAL_ALERT_MINUTES,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

private val CATEGORIES = listOf(
    "crash" to R.string.bug_cat_crash,
    "ticket" to R.string.bug_cat_ticket,
    "departures" to R.string.bug_cat_departures,
    "planner" to R.string.bug_cat_planner,
    "tracking" to R.string.bug_cat_tracking,
    "account" to R.string.bug_cat_account,
    "other" to R.string.bug_cat_other
)

/** A picked photo copied into the app (report_images/), so an unsent draft keeps it; returns the copy's file URI. */
private fun keepReportImage(context: Context, uri: Uri): Uri {
    val dir = java.io.File(context.filesDir, "report_images").apply { mkdirs() }
    val file = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
    file.writeBytes(Base64.decode(encodeReportImage(context, uri), Base64.NO_WRAP))
    return Uri.fromFile(file)
}

/** A picked photo for a report: at most 1600 px on the longer side, re-encoded as JPEG (which drops EXIF, e.g. GPS), base64. */
private fun encodeReportImage(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        // ponytail: Android 7-8 ignore the photo's EXIF rotation; add androidx.exifinterface if testers there need it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("Can't read the image")
        val scale = minOf(1f, 1600f / maxOf(decoded.width, decoded.height))
        Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BugReportForm(viewModel: AppViewModel, onDone: () -> Unit, feature: Boolean = false) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val ticket by viewModel.ticketState.collectAsState()
    val app by viewModel.appState.collectAsState()
    val scope = rememberCoroutineScope()
    // An unsent report comes back as it was left: text, name, category, logs switch and photos (EM-0027, EM-0031).
    val prefs = remember { com.ksjd.testem.CredentialsManager(context) }
    val draft = remember { prefs.getReportDraft(feature) }
    var category by rememberSaveable { mutableStateOf(draft?.category?.ifBlank { null } ?: if (feature) "feature" else "other") }
    var title by rememberSaveable { mutableStateOf(draft?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(draft?.description.orEmpty()) }
    // Signed-in users get their name and email filled in; they can change or clear both.
    // Only while unlocked: the name and email belong to the protected account.
    val known = app.hasAccount && app.isAppUnlocked
    var name by rememberSaveable { mutableStateOf(draft?.name ?: if (known) ticket.userName else "") }
    var email by rememberSaveable { mutableStateOf(draft?.email ?: if (known) app.email else "") }
    // A suggestion needs no logs.
    var attachLogs by rememberSaveable { mutableStateOf(draft?.attachLogs ?: !feature) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var sentCode by rememberSaveable { mutableStateOf("") }
    var logs by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(draft?.images.orEmpty().map(Uri::parse).filter { it.path?.let { p -> java.io.File(p).exists() } == true }) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { picked ->
        // Kept as small copies in the app, so a draft still has its photos after the app was closed.
        scope.launch {
            val copies = withContext(Dispatchers.IO) { picked.mapNotNull { runCatching { keepReportImage(context, it) }.getOrNull() } }
            images = (images + copies).distinct().take(5)
        }
    }
    LaunchedEffect(category, title, description, name, email, attachLogs, images) {
        val empty = title.isBlank() && description.isBlank() && images.isEmpty()
        prefs.saveReportDraft(feature, if (empty) null else com.ksjd.testem.CredentialsManager.ReportDraft(
            category, title, description, name, email, attachLogs, images.map(Uri::toString)))
    }
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { AppLogs.collect(context) } }

    if (sentCode.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(if (feature) R.string.feature_sent_title else R.string.bug_sent_title, sentCode)) },
            text = { Text(stringResource(if (feature) R.string.feature_sent_body else R.string.bug_sent_body)) },
            confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.privacy_done)) } }
        )
    }
    if (!HubClient.isConfigured) {
        Notice(stringResource(R.string.bug_not_configured), modifier = Modifier.padding(16.dp), tone = NoticeTone.Warning)
        return
    }
    val emailOk = email.isBlank() || Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val invalidEmail = stringResource(R.string.bug_invalid_email)

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!feature) SectionLabel(stringResource(R.string.bug_category), modifier = Modifier.padding(start = 0.dp))
        if (!feature) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORIES.forEach { (id, label) ->
                FilterChip(selected = category == id, onClick = { category = id }, label = { Text(stringResource(label)) },
                    leadingIcon = if (category == id) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
            }
        }
        OutlinedTextField(title, { if (it.length <= 140) title = it }, label = { Text(stringResource(if (feature) R.string.feature_summary else R.string.bug_summary)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { if (it.length <= 10_000) description = it }, label = { Text(stringResource(if (feature) R.string.feature_description else R.string.bug_description)) },
            minLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text(stringResource(R.string.bug_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            email, { if (it.length <= 200) email = it },
            label = { Text(stringResource(R.string.bug_email)) },
            supportingText = { Text(if (emailOk) stringResource(R.string.bug_email_hint) else invalidEmail) },
            isError = !emailOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (email.isBlank() && !feature) {
        Notice(stringResource(R.string.bug_no_email), modifier = Modifier.padding(horizontal = 16.dp), tone = NoticeTone.Warning)
    }
    Spacer(Modifier.height(8.dp))
    ListGroup {
        ListRow(
            stringResource(R.string.bug_images),
            subtitle = if (images.isEmpty()) stringResource(R.string.bug_images_hint) else stringResource(R.string.bug_images_count, images.size),
            onClick = if (images.size < 5) ({ pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) else null
        )
    }
    if (images.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { i, uri ->
            InputChip(selected = false, onClick = { images = images - uri; uri.path?.let { java.io.File(it).delete() } }, label = { Text(stringResource(R.string.bug_image, i + 1)) },
                trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.bug_image_remove), Modifier.size(18.dp)) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (!feature) ListGroup {
        ListRow(
            stringResource(R.string.bug_logs),
            subtitle = stringResource(R.string.bug_logs_hint),
            onClick = { attachLogs = !attachLogs },
            trailing = { Switch(attachLogs, { attachLogs = it }) }
        )
        if (attachLogs) {
            GroupDivider()
            ListRow(stringResource(if (showLogs) R.string.bug_logs_hide else R.string.bug_logs_show), onClick = { showLogs = !showLogs })
        }
    }
    if (attachLogs && showLogs) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 320.dp)
        ) {
            SelectionContainer {
                Text(
                    logs.takeLast(20_000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = consent, onCheckedChange = { consent = it })
        Text(
            stringResource(if (feature) R.string.feature_consent else R.string.bug_consent),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 12.dp, end = 8.dp)
        )
    }
    if (error.isNotEmpty()) Notice(error, modifier = Modifier.padding(horizontal = 16.dp), tone = NoticeTone.Error)
    val failed = stringResource(R.string.bug_failed, "%s")
    Button(
        onClick = {
            sending = true
            error = ""
            scope.launch {
                runCatching {
                    val encoded = withContext(Dispatchers.IO) { images.map { encodeReportImage(context, it) } }
                    HubClient.submitBug(
                        BugReport(category, title.trim(), description.trim(), name.trim(), email.trim(), AppLogs.device(),
                            if (attachLogs) logs else "", kind = if (feature) "feature" else "bug", images = encoded)
                    )
                }.onSuccess {
                    sentCode = it
                    Haptics.perform(view, Haptics.CONFIRM)
                    images.forEach { u -> u.path?.let { p -> java.io.File(p).delete() } }
                    images = emptyList(); title = ""; description = ""
                    prefs.saveReportDraft(feature, null)
                }
                    .onFailure { error = failed.replace("%s", it.message ?: "?"); Haptics.perform(view, Haptics.REJECT) }
                sending = false
            }
        },
        // Only logs are essential: a description is needed only when no logs are attached.
        enabled = consent && !sending && emailOk && (attachLogs || description.isNotBlank()),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) { Text(stringResource(if (sending) R.string.bug_sending else if (feature) R.string.feature_send else R.string.bug_send)) }
}
