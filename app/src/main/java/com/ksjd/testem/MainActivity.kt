package com.ksjd.testem

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ksjd.testem.ui.theme.TestEMTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.unit.TextUnit
import android.view.WindowManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: QRDaemonViewModel = viewModel()
            val appState by viewModel.appState.collectAsState()
            val activePreset = appState.themePresets
                .firstOrNull { it.id == appState.selectedThemeId }
                ?: appState.themePresets.firstOrNull()

            TestEMTheme(themePreset = activePreset) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QRDaemonApp(viewModel, this)
                }
            }
        }
    }
}

@Composable
fun QRDaemonApp(viewModel: QRDaemonViewModel, context: ComponentActivity) {
    val appState by viewModel.appState.collectAsState()
    val qrState by viewModel.qrState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadSavedCredentials(context.applicationContext)
    }
    
    if (appState.isLoggedIn) {
        QRDaemonScreen(viewModel, qrState, appState, context)
    } else {
        LoginScreen(viewModel, appState, context)
    }
}

@Composable
fun LoginScreen(viewModel: QRDaemonViewModel, appState: AppState, context: ComponentActivity) {
    var email by remember(appState.email) { mutableStateOf(appState.email) }
    var password by remember(appState.password) { mutableStateOf(appState.password) }
    
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "testEM",
            fontSize = 32.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            "Real-time QR Token Generator",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrect = false)
        )
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        
        if (appState.loginError.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    appState.loginError,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        Button(
            onClick = { viewModel.loginAndRemember(context.applicationContext, email, password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !appState.isLoading && email.isNotEmpty() && password.isNotEmpty()
        ) {
            if (appState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text("Login")
            }
        }
    }
}

@Composable
fun QRDaemonScreen(
    viewModel: QRDaemonViewModel,
    qrState: QRState,
    appState: AppState,
    context: ComponentActivity
) {
    var showAccountDialog by remember { mutableStateOf(false) }
    var showNfcDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFullscreenQr by remember { mutableStateOf(false) }
    var nfcUidToShow by remember { mutableStateOf("") }

    if (showAccountDialog) {
        AccountDialog(
            qrState = qrState,
            appState = appState,
            onDismiss = { showAccountDialog = false }
        )
    }
    if (showNfcDialog) {
        NfcUidDialog(
            uid = nfcUidToShow,
            onDismiss = { showNfcDialog = false }
        )
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            appState = appState,
            context = context,
            onBack = { showSettings = false }
        )
        return
    }

    if (showFullscreenQr) {
        FullscreenQrDialog(
            qrState = qrState,
            context = context,
            onDismiss = { showFullscreenQr = false }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                val name = qrState.userName.ifBlank { "testEM" }
                val topBarStyle = MaterialTheme.typography.titleMedium
                TextButton(onClick = { showAccountDialog = true }) {
                    Text(
                        name,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = topBarStyle
                    )
                }
            },
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        )
        
        LayoutContent(
            appState = appState,
            qrState = qrState,
            onShowFullscreenQr = { showFullscreenQr = true },
            onToggleNfc = {
                val createdUid = viewModel.toggleNfc(context.applicationContext)
                if (!createdUid.isNullOrBlank()) {
                    nfcUidToShow = createdUid
                    showNfcDialog = true
                }
            },
            onTogglePolling = { isPolling ->
                if (isPolling) {
                    viewModel.stopPolling()
                } else {
                    viewModel.startPolling()
                }
            }
        )
    }
}

@Composable
fun LayoutContent(
    appState: AppState,
    qrState: QRState,
    onShowFullscreenQr: () -> Unit,
    onToggleNfc: () -> Unit,
    onTogglePolling: (Boolean) -> Unit
) {
    val order = if (appState.layoutOrder.isNotEmpty()) {
        appState.layoutOrder
    } else {
        defaultLayoutOrderIds()
    }
    val hidden = appState.hiddenSections
    val sections = order.mapNotNull { id ->
        when (id) {
            "status" -> if (hidden.contains(id)) null else LayoutSectionContent { StatusCard(qrState) }
            "qr" -> LayoutSectionContent { QRCodeDisplay(qrState, onShowFullscreenQr) }
            "nfc" -> if (hidden.contains(id)) null else LayoutSectionContent {
                AccountActionsCard(
                    nfcEnabled = appState.nfcEnabled,
                    isQrReady = qrState.qrBitmap != null,
                    onToggleNfc = onToggleNfc
                )
            }
            "controls" -> LayoutSectionContent {
                ControlButtonsRow(qrState, onTogglePolling)
            }
            "error" -> {
                if (hidden.contains(id)) {
                    null
                } else
                if (qrState.errorMessage.isNotEmpty()) {
                    LayoutSectionContent {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                qrState.errorMessage,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        sections.forEachIndexed { index, section ->
            section.content()
            if (index < sections.lastIndex) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: QRDaemonViewModel,
    appState: AppState,
    context: ComponentActivity,
    onBack: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var primaryHex by remember { mutableStateOf("") }
    var secondaryHex by remember { mutableStateOf("") }
    var tertiaryHex by remember { mutableStateOf("") }

    val primaryColor = parseColorHex(primaryHex)
    val secondaryColor = parseColorHex(secondaryHex)
    val tertiaryColor = parseColorHex(tertiaryHex)
    val canSavePreset = presetName.isNotBlank()
        && primaryColor != null
        && secondaryColor != null
        && tertiaryColor != null

    if (showLogoutDialog) {
        LogoutDialog(
            onConfirm = {
                viewModel.logout()
                showLogoutDialog = false
                onBack()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            navigation = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
            title = {
                Text(
                    "Settings",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Theme presets",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    appState.themePresets.forEach { preset ->
                        ThemePresetRow(
                            preset = preset,
                            selected = preset.id == appState.selectedThemeId,
                            onSelect = {
                                viewModel.selectThemePreset(
                                    context.applicationContext,
                                    preset.id
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Layout",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val order = if (appState.layoutOrder.isNotEmpty()) {
                        appState.layoutOrder
                    } else {
                        defaultLayoutOrderIds()
                    }
                    val titles = layoutSectionTitles()
                    val hideable = setOf("status", "nfc", "error")
                    val hidden = appState.hiddenSections
                    order.forEachIndexed { index, id ->
                        LayoutOrderRow(
                            title = titles[id] ?: id,
                            canMoveUp = index > 0,
                            canMoveDown = index < order.lastIndex,
                            onMoveUp = {
                                viewModel.moveLayoutItem(
                                    context.applicationContext,
                                    id,
                                    -1
                                )
                            },
                            onMoveDown = {
                                viewModel.moveLayoutItem(
                                    context.applicationContext,
                                    id,
                                    1
                                )
                            },
                            canToggleVisibility = hideable.contains(id),
                            visible = !hidden.contains(id),
                            onToggleVisibility = { visible ->
                                viewModel.setSectionHidden(
                                    context.applicationContext,
                                    id,
                                    !visible
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Create preset",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Preset name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = primaryHex,
                        onValueChange = { primaryHex = it },
                        label = { Text("Primary hex (RRGGBB or AARRGGBB)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secondaryHex,
                        onValueChange = { secondaryHex = it },
                        label = { Text("Secondary hex (RRGGBB or AARRGGBB)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tertiaryHex,
                        onValueChange = { tertiaryHex = it },
                        label = { Text("Tertiary hex (RRGGBB or AARRGGBB)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorPreviewSwatch(label = "P", color = primaryColor)
                        ColorPreviewSwatch(label = "S", color = secondaryColor)
                        ColorPreviewSwatch(label = "T", color = tertiaryColor)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.addThemePreset(
                                context.applicationContext,
                                presetName.trim(),
                                primaryColor ?: 0xFF000000,
                                secondaryColor ?: 0xFF000000,
                                tertiaryColor ?: 0xFF000000
                            )
                            presetName = ""
                            primaryHex = ""
                            secondaryHex = ""
                            tertiaryHex = ""
                        },
                        enabled = canSavePreset,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save preset")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Account",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            "Logout",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThemePresetRow(
    preset: ThemePreset,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.name, fontWeight = FontWeight.Medium)
            Text(
                "Primary / Secondary / Tertiary",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorPreviewSwatch(color = preset.primary)
            ColorPreviewSwatch(color = preset.secondary)
            ColorPreviewSwatch(color = preset.tertiary)
        }
    }
}

@Composable
fun ColorPreviewSwatch(label: String? = null, color: Long?) {
    val swatchColor = color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(swatchColor, CircleShape)
    )
    if (!label.isNullOrBlank()) {
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
fun StatusCard(qrState: QRState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (qrState.isPolling) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status:", fontWeight = FontWeight.Bold)
                Text(
                    if (qrState.isPolling) "Polling Active" else "Polling Paused",
                    color = if (qrState.isPolling) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Last Update: ${formatTime(qrState.lastUpdateTime)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (qrState.statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    qrState.statusMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun QRCodeDisplay(qrState: QRState) {
    QRCodeDisplay(qrState = qrState, onShowFullscreen = {})
}

@Composable
fun QRCodeDisplay(qrState: QRState, onShowFullscreen: () -> Unit) {
    var showTokenInfo by remember { mutableStateOf(false) }
    if (showTokenInfo) {
        TokenInfoDialog(
            qrState = qrState,
            onDismiss = { showTokenInfo = false }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Current QR Code",
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showTokenInfo = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Token info"
                    )
                }
            }
            
            if (qrState.qrBitmap != null) {
                val size = QRDaemonConfig.QR_CODE_SIZE.dp
                Image(
                    bitmap = qrState.qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(size)
                        .clickable(onClick = onShowFullscreen)
                )
            } else {
                PlaceholderQRCode(onShowFullscreen)
            }
        }
    }
}

@Composable
fun PlaceholderQRCode(onShowFullscreen: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(QRDaemonConfig.QR_CODE_SIZE.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onShowFullscreen),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "No Token",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FullscreenQrDialog(
    qrState: QRState,
    context: ComponentActivity,
    onDismiss: () -> Unit
) {
    val window = context.window
    val previousBrightness = remember { window.attributes.screenBrightness }
    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = 1f
        window.attributes = params
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val restore = window.attributes
            restore.screenBrightness = previousBrightness
            window.attributes = restore
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            if (qrState.qrBitmap != null) {
                Image(
                    bitmap = qrState.qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code Fullscreen",
                    modifier = Modifier.size(250.dp)
                )
            } else {
                Text(
                    "No Token",
                    color = Color.Black
                )
            }
        }
    }
}

data class LayoutSectionContent(val content: @Composable () -> Unit)

@Composable
fun LayoutOrderRow(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canToggleVisibility: Boolean,
    visible: Boolean,
    onToggleVisibility: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium
            )
            if (canToggleVisibility) {
                Text(
                    if (visible) "Visible" else "Hidden",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Always visible",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (canToggleVisibility) {
            TextButton(onClick = { onToggleVisibility(!visible) }) {
                Text(if (visible) "Hide" else "Show")
            }
        }
        TextButton(onClick = onMoveUp, enabled = canMoveUp) {
            Text("Up")
        }
        TextButton(onClick = onMoveDown, enabled = canMoveDown) {
            Text("Down")
        }
    }
}

private fun layoutSectionTitles(): Map<String, String> {
    return mapOf(
        "status" to "Polling status",
        "qr" to "QR code",
        "nfc" to "NFC button",
        "controls" to "Controls",
        "error" to "Errors"
    )
}

private fun defaultLayoutOrderIds(): List<String> {
    return listOf(
        "status",
        "qr",
        "nfc",
        "controls",
        "error"
    )
}

@Composable
fun AccountActionsCard(nfcEnabled: Boolean, isQrReady: Boolean, onToggleNfc: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = onToggleNfc,
                modifier = Modifier.fillMaxWidth(),
                enabled = isQrReady
            ) {
                Text(if (nfcEnabled) "Switch to QR" else "Switch to NFC")
            }
        }
    }
}

@Composable
fun NfcUidDialog(uid: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NFC UID") },
        text = {
            Column {
                Text("Copy this UID to set it on the website:", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                SelectableText(uid, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(uid))
                onDismiss()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun AccountDialog(qrState: QRState, appState: AppState, onDismiss: () -> Unit) {
    val details = qrState.accountDetails
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account") },
        text = {
            Column {
                Text("Name: ${qrState.userName.ifBlank { "testEM" }}", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Email: ${appState.email}", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("SNR: ${appState.serialNumber}", fontSize = 12.sp)
                if (appState.nfcUid.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("NFC UID: ${appState.nfcUid}", fontSize = 12.sp)
                }

                if (details.cardTypeName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Card Type: ${details.cardTypeName}", fontSize = 12.sp)
                }
                if (details.organizationName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Organization: ${details.organizationName}", fontSize = 12.sp)
                }
                if (details.cardValidFrom > 0 || details.cardValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Card Valid: ${formatDate(details.cardValidFrom)} - ${formatDate(details.cardValidTo)}",
                        fontSize = 12.sp
                    )
                }
                if (details.ticketValidFrom > 0 || details.ticketValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Ticket Valid: ${formatDate(details.ticketValidFrom)} - ${formatDate(details.ticketValidTo)}",
                        fontSize = 12.sp
                    )
                }
                if (details.discountValidFrom > 0 || details.discountValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Discount Valid: ${formatDate(details.discountValidFrom)} - ${formatDate(details.discountValidTo)}",
                        fontSize = 12.sp
                    )
                }
                if (details.creditLastBalance != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val currency = details.currencySymbol.ifBlank { "" }
                    Text(
                        "Credit: ${details.creditLastBalance}$currency",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ControlButtonsRow(
    qrState: QRState,
    onTogglePolling: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onTogglePolling(qrState.isPolling) },
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (qrState.isPolling) 
                    MaterialTheme.colorScheme.errorContainer 
                else 
                    MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                if (qrState.isPolling) "Stop" else "Get QR",
                color = if (qrState.isPolling) 
                    MaterialTheme.colorScheme.onErrorContainer 
                else 
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun TokenInfoDialog(qrState: QRState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token Information") },
        text = {
            if (qrState.tokenHex.isEmpty()) {
                Text(
                    "Waiting for token…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                Column {
                    SelectableText(
                        "HEX: ${qrState.tokenHex}",
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SelectableText(
                        "B64: ${qrState.tokenBase64}",
                        fontSize = 10.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logout?") },
        text = { Text("Are you sure you want to logout?") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Yes, Logout")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTime(timestampMs: Long): String {
    return if (timestampMs == 0L) {
        "Never"
    } else {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestampMs))
    }
}

private fun formatDate(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return "-"
    val timestampMs = if (timestampSeconds < 10_000_000_000L) {
        timestampSeconds * 1000L
    } else {
        timestampSeconds
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

@Composable
fun SelectableText(text: String, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = fontSize,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
    navigation: @Composable () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigation()
                Spacer(modifier = Modifier.width(8.dp))
                title()
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}