package com.ksjd.testem

import android.app.TimePickerDialog
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ksjd.testem.ui.theme.TestEMTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: QRDaemonViewModel = viewModel()
            val appState by viewModel.appState.collectAsState()
            val activePreset = appState.themePresets
                .firstOrNull { it.id == appState.selectedThemeId }
                ?: appState.themePresets.firstOrNull()

            TestEMTheme(
                themePreset = activePreset,
                amoledMode = appState.amoledEnabled
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = Color.Transparent
                ) {
                    QRDaemonApp(viewModel, this)
                }
            }
        }
    }
}

@Composable
fun QRDaemonApp(viewModel: QRDaemonViewModel, context: FragmentActivity) {
    val appState by viewModel.appState.collectAsState()
    val qrState by viewModel.qrState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showTimetables by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadSavedCredentials(context.applicationContext)
    }

    LaunchedEffect(
        appState.isAppUnlocked,
        appState.isLoggedIn,
        appState.isLoading,
        appState.email,
        appState.password
    ) {
        if (appState.isAppUnlocked && !appState.isLoggedIn && !appState.isLoading && viewModel.hasSavedCredentials()) {
            viewModel.loginWithSavedCredentials(context.applicationContext)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> {
                    if (!context.isChangingConfigurations) {
                        viewModel.onAppBackgrounded()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showTimetables) {
        TimetablesScreen(
            qrState = qrState,
            viewModel = viewModel,
            onBack = { showTimetables = false }
        )
        return
    }

    when {
        !appState.isPinSet -> PinSetupScreen(viewModel, context, onOpenTimetables = { showTimetables = true })
        !appState.isAppUnlocked -> PinUnlockScreen(
            viewModel,
            appState,
            context,
            onOpenTimetables = { showTimetables = true }
        )
        appState.isLoggedIn -> QRDaemonScreen(viewModel, qrState, appState, context)
        else -> LoginScreen(viewModel, appState, context, onOpenTimetables = { showTimetables = true })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PinSetupScreen(
    viewModel: QRDaemonViewModel,
    context: FragmentActivity,
    onOpenTimetables: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val pinTooShortMessage = stringResource(R.string.pin_error_too_short)
    val pinMismatchMessage = stringResource(R.string.pin_error_mismatch)
    val focusManager = LocalFocusManager.current

    val canSubmit = pin.length >= 4 && confirmPin.length >= 4
    val submit = {
        error = when {
            pin.length < 4 -> pinTooShortMessage
            pin != confirmPin -> pinMismatchMessage
            else -> ""
        }
        if (error.isEmpty()) {
            viewModel.setPin(context.applicationContext, pin)
            pin = ""
            confirmPin = ""
        }
    }

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.pin_setup_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.pin_setup_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pin = it
                },
                label = { Text(stringResource(R.string.pin_label_range)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next
                ),
                visualTransformation = PasswordVisualTransformation(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = {
                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) confirmPin = it
                },
                label = { Text(stringResource(R.string.pin_label_confirm)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                visualTransformation = PasswordVisualTransformation(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSubmit) submit()
                    }
                )
            )

            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.pin_save_button))
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTimetables,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.timetables_open_button))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PinUnlockScreen(
    viewModel: QRDaemonViewModel,
    appState: AppState,
    context: FragmentActivity,
    onOpenTimetables: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val incorrectPinMessage = stringResource(R.string.pin_error_incorrect)
    val submit = {
        val ok = viewModel.verifyPin(context.applicationContext, pin)
        error = if (ok) "" else incorrectPinMessage
        if (ok) pin = ""
    }

    val biometricManager = remember { BiometricManager.from(context) }
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    val biometricsAvailable = canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS

    val prompt = rememberBiometricPrompt(
        activity = context,
        onSuccess = {
            viewModel.verifyPin(context.applicationContext, "", allowBlank = true)
        },
        onError = { message ->
            error = message
        },
        onFailedMessage = stringResource(R.string.biometric_not_recognized)
    )
    val promptTitle = stringResource(R.string.unlock_prompt_title)
    val promptSubtitle = stringResource(R.string.unlock_prompt_subtitle)
    val promptNegative = stringResource(R.string.unlock_prompt_use_pin)
    val promptInfo = remember(promptTitle, promptSubtitle, promptNegative) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle(promptSubtitle)
            .setNegativeButtonText(promptNegative)
            .build()
    }

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.unlock_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.unlock_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pin = it
                },
                label = { Text(stringResource(R.string.pin_label)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                visualTransformation = PasswordVisualTransformation(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submit() }
                )
            )

            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.unlock_button))
            }

            if (biometricsAvailable && appState.biometricEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { prompt.authenticate(promptInfo) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.use_biometrics))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTimetables,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.timetables_open_button))
            }
        }
    }
}

@Composable
fun rememberBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onFailedMessage: String
): BiometricPrompt {
    val onSuccessState by rememberUpdatedState(onSuccess)
    val onErrorState by rememberUpdatedState(onError)
    val onFailedMessageState by rememberUpdatedState(onFailedMessage)
    val executor = remember { ContextCompat.getMainExecutor(activity) }
    return remember {
        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccessState()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onErrorState(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onErrorState(onFailedMessageState)
                }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
fun LoginScreen(
    viewModel: QRDaemonViewModel,
    appState: AppState,
    context: FragmentActivity,
    onOpenTimetables: () -> Unit = {}
) {
    var email by remember(appState.email) { mutableStateOf(appState.email) }
    var password by remember(appState.password) { mutableStateOf(appState.password) }
    val focusManager = LocalFocusManager.current
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val emailAutofillNode = remember {
        AutofillNode(
            autofillTypes = listOf(AutofillType.EmailAddress, AutofillType.Username),
            onFill = { email = it }
        )
    }
    val passwordAutofillNode = remember {
        AutofillNode(
            autofillTypes = listOf(AutofillType.Password),
            onFill = { password = it }
        )
    }
    DisposableEffect(autofillTree, emailAutofillNode, passwordAutofillNode) {
        autofillTree += emailAutofillNode
        autofillTree += passwordAutofillNode
        onDispose {
            autofillTree.children.remove(emailAutofillNode.id)
            autofillTree.children.remove(passwordAutofillNode.id)
        }
    }

    val biometricManager = remember { BiometricManager.from(context) }
    val biometricsAvailable = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    var biometricError by remember { mutableStateOf("") }

    val doLogin = {
        viewModel.loginAndRemember(context.applicationContext, email, password)
    }
    val loginPrompt = rememberBiometricPrompt(
        activity = context,
        onSuccess = {
            biometricError = ""
            doLogin()
        },
        onError = { message ->
            biometricError = message
        },
        onFailedMessage = stringResource(R.string.biometric_not_recognized)
    )
    val loginPromptInfo = remember(
        appState.biometricEnabled,
        biometricsAvailable
    ) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.unlock_prompt_title))
            .setSubtitle(context.getString(R.string.unlock_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.unlock_prompt_use_pin))
            .build()
    }

    val canSubmit = !appState.isLoading && email.isNotEmpty() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            if (appState.biometricEnabled && biometricsAvailable) {
                loginPrompt.authenticate(loginPromptInfo)
            } else {
                biometricError = ""
                doLogin()
            }
        }
    }

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.app_name),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Text(
                stringResource(R.string.login_subtitle),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.email_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .onGloballyPositioned { coordinates ->
                                emailAutofillNode.boundingBox = coordinates.boundsInWindow()
                            }
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    autofill?.requestAutofillForNode(emailAutofillNode)
                                } else {
                                    autofill?.cancelAutofillForNode(emailAutofillNode)
                                }
                            },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrect = false,
                            imeAction = ImeAction.Next
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .onGloballyPositioned { coordinates ->
                                passwordAutofillNode.boundingBox = coordinates.boundsInWindow()
                            }
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    autofill?.requestAutofillForNode(passwordAutofillNode)
                                } else {
                                    autofill?.cancelAutofillForNode(passwordAutofillNode)
                                }
                            },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() }
                        )
                    )

                    if (appState.loginError.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                appState.loginError,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (biometricError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                biometricError,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = submit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = canSubmit
            ) {
                if (appState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.login_button))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTimetables,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.timetables_open_button))
            }
        }
    }
}

@Composable
fun QRDaemonScreen(
    viewModel: QRDaemonViewModel,
    qrState: QRState,
    appState: AppState,
    context: FragmentActivity
) {
    var showAccountDialog by remember { mutableStateOf(false) }
    var showNfcDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFullscreenQr by remember { mutableStateOf(false) }
    var showHistoryScreen by remember { mutableStateOf(false) }
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
    if (showHistoryScreen) {
        HistoryScreen(
            historyState = qrState.historyState,
            onBack = { showHistoryScreen = false },
            onRefresh = { viewModel.loadCardHistory() }
        )
        return
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

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            TopAppBar(
                title = {
                    val name = qrState.userName.ifBlank { stringResource(R.string.app_name) }
                    val topBarStyle = MaterialTheme.typography.titleMedium
                    TextButton(onClick = { showAccountDialog = true }) {
                        Text(
                            name,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = topBarStyle
                        )
                    }
                },
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_content_desc),
                                tint = MaterialTheme.colorScheme.onBackground
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
                },
                onShowHistory = {
                    showHistoryScreen = true
                    viewModel.loadCardHistory()
                }
            )
        }
    }
}

@Composable
fun LayoutContent(
    appState: AppState,
    qrState: QRState,
    onShowFullscreenQr: () -> Unit,
    onToggleNfc: () -> Unit,
    onTogglePolling: (Boolean) -> Unit,
    onShowHistory: () -> Unit
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
            "low_credit" -> if (hidden.contains(id)) {
                null
            } else {
                val credit = qrState.accountDetails.creditLastBalance
                if (credit != null && credit < appState.lowCreditWarningThreshold) {
                    LayoutSectionContent { LowCreditWarningCard(qrState.accountDetails) }
                } else {
                    null
                }
            }
            "qr" -> LayoutSectionContent { QRCodeDisplay(qrState, onShowFullscreenQr) }
            "nfc" -> if (hidden.contains(id)) null else LayoutSectionContent {
                AccountActionsCard(
                    nfcEnabled = appState.nfcEnabled,
                    isQrReady = qrState.qrBitmap != null,
                    onToggleNfc = onToggleNfc
                )
            }
            "controls" -> LayoutSectionContent {
                ControlButtonsRow(qrState, onTogglePolling, onShowHistory)
            }
            "error" -> {
                if (hidden.contains(id)) {
                    null
                } else if (qrState.errorMessage.isNotEmpty()) {
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
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
    context: FragmentActivity,
    onBack: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(SettingsPage.Root) }

    BackHandler {
        if (page == SettingsPage.Root) {
            onBack()
        } else {
            page = SettingsPage.Root
        }
    }

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

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            TopAppBar(
                navigation = {
                    IconButton(onClick = {
                        if (page == SettingsPage.Root) {
                            onBack()
                        } else {
                            page = SettingsPage.Root
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        when (page) {
                            SettingsPage.Root -> stringResource(R.string.settings_title)
                            SettingsPage.Layout -> stringResource(R.string.layout_title)
                            SettingsPage.Security -> stringResource(R.string.security_title)
                        },
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            )

            when (page) {
                SettingsPage.Root -> SettingsRootContent(
                    appState = appState,
                    context = context,
                    viewModel = viewModel,
                    onLogout = { showLogoutDialog = true },
                    onOpenLayout = { page = SettingsPage.Layout },
                    onOpenSecurity = { page = SettingsPage.Security }
                )
                SettingsPage.Layout -> LayoutSettingsContent(
                    appState = appState,
                    context = context,
                    viewModel = viewModel
                )
                SettingsPage.Security -> SecuritySettingsContent(
                    appState = appState,
                    context = context,
                    viewModel = viewModel
                )
            }
        }
    }
}

private enum class SettingsPage {
    Root,
    Layout,
    Security
}

@Composable
fun SettingsRootContent(
    appState: AppState,
    context: FragmentActivity,
    viewModel: QRDaemonViewModel,
    onLogout: () -> Unit,
    onOpenLayout: () -> Unit,
    onOpenSecurity: () -> Unit
) {
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
    val languageOptions = listOf(
        "sk" to stringResource(R.string.language_slovak_preferred),
        "en" to stringResource(R.string.language_english)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsSectionCard(title = stringResource(R.string.theme_presets_title)) {
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.amoled_mode_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.amoled_mode_subtitle),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = appState.amoledEnabled,
                    onCheckedChange = {
                        viewModel.setAmoledEnabled(context.applicationContext, it)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(title = stringResource(R.string.create_preset_title)) {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text(stringResource(R.string.preset_name_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = primaryHex,
                onValueChange = { primaryHex = it },
                label = { Text(stringResource(R.string.primary_hex_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = secondaryHex,
                onValueChange = { secondaryHex = it },
                label = { Text(stringResource(R.string.secondary_hex_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = tertiaryHex,
                onValueChange = { tertiaryHex = it },
                label = { Text(stringResource(R.string.tertiary_hex_label)) },
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.save_preset))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(title = stringResource(R.string.language_title)) {
            languageOptions.forEach { (code, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setLanguageCode(
                                context.applicationContext,
                                code
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = appState.languageCode == code,
                        onClick = {
                            viewModel.setLanguageCode(
                                context.applicationContext,
                                code
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        var lowCreditThresholdInput by remember { mutableStateOf("") }
        var lastAppliedLowCreditThreshold by remember { mutableStateOf<Double?>(null) }

        LaunchedEffect(appState.lowCreditWarningThreshold) {
            val threshold = appState.lowCreditWarningThreshold.coerceAtLeast(0.0)
            lowCreditThresholdInput = threshold.toString()
            lastAppliedLowCreditThreshold = threshold
        }

        SettingsSectionCard(title = stringResource(R.string.low_credit_threshold_title)) {
            Text(
                text = stringResource(R.string.low_credit_threshold_description),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = lowCreditThresholdInput,
                onValueChange = {
                    val normalized = it.replace(',', '.')
                    val isValidInput = normalized.matches(Regex("^[0-9]*([.][0-9]{0,2})?$"))
                    if (it.length <= 8 && isValidInput) {
                        lowCreditThresholdInput = it
                        val value = normalized.toDoubleOrNull()
                        if (value != null && value >= 0.0 && value != lastAppliedLowCreditThreshold) {
                            viewModel.setLowCreditWarningThreshold(
                                context.applicationContext,
                                value
                            )
                            lastAppliedLowCreditThreshold = value
                        }
                    }
                },
                label = { Text(stringResource(R.string.low_credit_threshold_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(
            title = stringResource(R.string.layout_title),
            subtitle = stringResource(R.string.layout_subtitle)
        ) {
            Button(
                onClick = onOpenLayout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.open_layout_settings))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(
            title = stringResource(R.string.security_title),
            subtitle = stringResource(R.string.security_subtitle)
        ) {
            Button(
                onClick = onOpenSecurity,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.open_security_settings))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(title = stringResource(R.string.account_title)) {
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    stringResource(R.string.logout),
                    color = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}

@Composable
fun LayoutSettingsContent(
    appState: AppState,
    context: FragmentActivity,
    viewModel: QRDaemonViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        val order = if (appState.layoutOrder.isNotEmpty()) {
            appState.layoutOrder
        } else {
            defaultLayoutOrderIds()
        }
        val titles = mapOf(
            "status" to stringResource(R.string.section_polling_status),
            "low_credit" to stringResource(R.string.section_low_credit_warning),
            "qr" to stringResource(R.string.section_qr_code),
            "nfc" to stringResource(R.string.section_nfc_button),
            "controls" to stringResource(R.string.section_controls),
            "error" to stringResource(R.string.section_errors)
        )
        val hideable = setOf("status", "low_credit", "nfc", "error")
        val hidden = appState.hiddenSections
        order.forEachIndexed { index, id ->
            SettingsItemCard {
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
            if (index < order.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun SecuritySettingsContent(
    appState: AppState,
    context: FragmentActivity,
    viewModel: QRDaemonViewModel
) {
    var showChangePin by remember { mutableStateOf(false) }
    var customTimeout by remember { mutableStateOf("") }
    var lastAppliedTimeout by remember { mutableStateOf<Int?>(null) }
    val timeoutOptions = listOf(0, 30, 60, 300)

    LaunchedEffect(appState.lockTimeoutSeconds) {
        if (appState.lockTimeoutSeconds !in timeoutOptions) {
            customTimeout = appState.lockTimeoutSeconds.toString()
            lastAppliedTimeout = appState.lockTimeoutSeconds
        }
    }

    if (showChangePin) {
        ChangePinDialog(
            onDismiss = { showChangePin = false },
            onConfirm = { currentPin, newPin ->
                viewModel.changePin(
                    context.applicationContext,
                    currentPin,
                    newPin
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsSectionCard(title = stringResource(R.string.lock_timeout_title)) {
            timeoutOptions.forEach { seconds ->
                val label = when (seconds) {
                    0 -> stringResource(R.string.lock_timeout_immediately)
                    30 -> stringResource(R.string.lock_timeout_30_seconds)
                    60 -> stringResource(R.string.lock_timeout_1_minute)
                    300 -> stringResource(R.string.lock_timeout_5_minutes)
                    else -> stringResource(R.string.lock_timeout_seconds, seconds)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setLockTimeoutSeconds(
                                context.applicationContext,
                                seconds
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = appState.lockTimeoutSeconds == seconds,
                        onClick = {
                            viewModel.setLockTimeoutSeconds(
                                context.applicationContext,
                                seconds
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = customTimeout,
                onValueChange = {
                    if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                        customTimeout = it
                        val value = it.toIntOrNull()
                        if (value != null && value >= 0 && value != lastAppliedTimeout) {
                            viewModel.setLockTimeoutSeconds(
                                context.applicationContext,
                                value
                            )
                            lastAppliedTimeout = value
                        }
                    }
                },
                label = { Text(stringResource(R.string.custom_seconds_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(title = stringResource(R.string.biometrics_label)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.biometrics_label),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = appState.biometricEnabled,
                    onCheckedChange = {
                        viewModel.setBiometricEnabled(
                            context.applicationContext,
                            it
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard(title = stringResource(R.string.change_pin_button)) {
            Button(
                onClick = { showChangePin = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.change_pin_button))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChangePinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Boolean
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val enterCurrentMessage = stringResource(R.string.change_pin_error_enter_current)
    val newPinShortMessage = stringResource(R.string.change_pin_error_new_short)
    val pinMismatchMessage = stringResource(R.string.change_pin_error_mismatch)
    val incorrectPinMessage = stringResource(R.string.change_pin_error_incorrect)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_pin_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.current_pin_label),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = {
                        if (it.length <= 8 && it.all { ch -> ch.isDigit() }) currentPin = it
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.new_pin_label),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = newPin,
                    onValueChange = {
                        if (it.length <= 8 && it.all { ch -> ch.isDigit() }) newPin = it
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.confirm_new_pin_label),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 8 && it.all { ch -> ch.isDigit() }) confirmPin = it
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    currentPin.length < 4 -> enterCurrentMessage
                    newPin.length < 4 -> newPinShortMessage
                    newPin != confirmPin -> pinMismatchMessage
                    else -> ""
                }
                if (error.isNotEmpty()) return@Button
                val ok = onConfirm(currentPin, newPin)
                if (ok) {
                    onDismiss()
                } else {
                    error = incorrectPinMessage
                }
            }) {
                Text(stringResource(R.string.update_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
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
                stringResource(R.string.preset_palette_label),
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
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.status_label), fontWeight = FontWeight.Bold)
                StatusChip(
                    text = if (qrState.isPolling) {
                        stringResource(R.string.polling_active)
                    } else {
                        stringResource(R.string.polling_paused)
                    },
                    isActive = qrState.isPolling
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(
                    R.string.last_update_label,
                    formatTime(qrState.lastUpdateTime, stringResource(R.string.never_label))
                ),
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
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
                    stringResource(R.string.current_qr_code),
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showTokenInfo = true }) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.token_info_content_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }

            if (qrState.qrBitmap != null) {
                val size = QRDaemonConfig.QR_CODE_SIZE.dp
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(onClick = onShowFullscreen),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrState.qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_code_content_desc),
                        modifier = Modifier.size(size - 32.dp)
                    )
                }
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
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onShowFullscreen),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.no_token),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.token_waiting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun FullscreenQrDialog(
    qrState: QRState,
    context: FragmentActivity,
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
                    contentDescription = stringResource(R.string.qr_code_fullscreen_content_desc),
                    modifier = Modifier.size(250.dp)
                )
            } else {
                Text(
                    stringResource(R.string.no_token),
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
                    if (visible) stringResource(R.string.visible_label) else stringResource(R.string.hidden_label),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.always_visible_label),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (canToggleVisibility) {
            TextButton(onClick = { onToggleVisibility(!visible) }) {
                Text(if (visible) stringResource(R.string.hide_action) else stringResource(R.string.show_action))
            }
        }
        TextButton(onClick = onMoveUp, enabled = canMoveUp) {
            Text(stringResource(R.string.move_up))
        }
        TextButton(onClick = onMoveDown, enabled = canMoveDown) {
            Text(stringResource(R.string.move_down))
        }
    }
}

private fun defaultLayoutOrderIds(): List<String> {
    return listOf(
        "status",
        "low_credit",
        "qr",
        "nfc",
        "controls",
        "error"
    )
}

@Composable
fun LowCreditWarningCard(details: AccountDetails) {
    val balance = details.creditLastBalance ?: return
    val currency = details.currencySymbol.ifBlank { "€" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.low_credit_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.low_credit_message, balance, currency),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun AccountActionsCard(nfcEnabled: Boolean, isQrReady: Boolean, onToggleNfc: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = onToggleNfc,
                modifier = Modifier.fillMaxWidth(),
                enabled = isQrReady,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (nfcEnabled) stringResource(R.string.switch_to_qr) else stringResource(R.string.switch_to_nfc))
            }
        }
    }
}

@Composable
fun NfcUidDialog(uid: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nfc_uid_title)) },
        text = {
            Column {
                Text(stringResource(R.string.nfc_uid_instructions), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                SelectableText(uid, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(uid))
                onDismiss()
            }) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun AccountDialog(qrState: QRState, appState: AppState, onDismiss: () -> Unit) {
    val details = qrState.accountDetails
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_title)) },
        text = {
            Column {
                val appName = stringResource(R.string.app_name)
                val dateUnknown = stringResource(R.string.date_unknown)
                Text(
                    stringResource(
                        R.string.account_name_label,
                        qrState.userName.ifBlank { appName }
                    ),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(stringResource(R.string.account_email_label, appState.email), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(stringResource(R.string.account_snr_label, appState.serialNumber), fontSize = 12.sp)
                if (appState.nfcUid.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.account_nfc_uid_label, appState.nfcUid), fontSize = 12.sp)
                }

                if (details.cardTypeName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.account_card_type_label, details.cardTypeName), fontSize = 12.sp)
                }
                if (details.organizationName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.account_organization_label, details.organizationName), fontSize = 12.sp)
                }
                if (details.cardValidFrom > 0 || details.cardValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.account_card_valid_label,
                            formatDate(details.cardValidFrom, dateUnknown),
                            formatDate(details.cardValidTo, dateUnknown)
                        ),
                        fontSize = 12.sp
                    )
                }
                if (details.ticketValidFrom > 0 || details.ticketValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.account_ticket_valid_label,
                            formatDate(details.ticketValidFrom, dateUnknown),
                            formatDate(details.ticketValidTo, dateUnknown)
                        ),
                        fontSize = 12.sp
                    )
                }
                if (details.discountValidFrom > 0 || details.discountValidTo > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.account_discount_valid_label,
                            formatDate(details.discountValidFrom, dateUnknown),
                            formatDate(details.discountValidTo, dateUnknown)
                        ),
                        fontSize = 12.sp
                    )
                }
                if (details.creditLastBalance != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val currency = details.currencySymbol.ifBlank { "" }
                    Text(
                        stringResource(R.string.account_credit_label, details.creditLastBalance, currency),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun ControlButtonsRow(
    qrState: QRState,
    onTogglePolling: (Boolean) -> Unit,
    onShowHistory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onTogglePolling(qrState.isPolling) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (qrState.isPolling)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (qrState.isPolling) stringResource(R.string.stop_button) else stringResource(R.string.get_qr_button),
                color = if (qrState.isPolling)
                    MaterialTheme.colorScheme.onError
                else
                    MaterialTheme.colorScheme.onPrimary
            )
        }
        OutlinedButton(
            onClick = onShowHistory,
            enabled = qrState.qrBitmap != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            if (qrState.historyState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(stringResource(R.string.history_button))
            }
        }
    }
}

@Composable
fun TimetablesScreen(
    qrState: QRState,
    viewModel: QRDaemonViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state = qrState.timetableState
    var citySlug by remember(state.citySlug) { mutableStateOf(state.citySlug) }
    var fromInput by remember(state.fromInput) { mutableStateOf(state.fromInput) }
    var toInput by remember(state.toInput) { mutableStateOf(state.toInput) }
    var timeInput by remember(state.timeInput) { mutableStateOf(state.timeInput) }
    var directOnly by remember(state.directOnly) { mutableStateOf(state.directOnly) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        LaunchedEffect(showTimePicker) {
            val now = Calendar.getInstance()
            val parsed = Regex("^(\\d{1,2}):(\\d{2})$")
                .find(timeInput)
                ?.groupValues
                ?.drop(1)
                ?.mapNotNull { it.toIntOrNull() }
            val initialHour = parsed?.getOrNull(0)?.coerceIn(0, 23) ?: now.get(Calendar.HOUR_OF_DAY)
            val initialMinute = parsed?.getOrNull(1)?.coerceIn(0, 59) ?: now.get(Calendar.MINUTE)
            val dialog = TimePickerDialog(
                context,
                { _, hour, minute ->
                    timeInput = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                },
                initialHour,
                initialMinute,
                true
            )
            dialog.setOnDismissListener { showTimePicker = false }
            dialog.show()
        }
    }

    val cityOptions = listOf(
        "slovensko" to stringResource(R.string.timetables_city_slovakia),
        "zvolen" to stringResource(R.string.timetables_city_zvolen),
        "banskabystrica" to stringResource(R.string.timetables_city_banska_bystrica)
    )

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigation = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.timetables_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SettingsSectionCard(
                        title = stringResource(R.string.timetables_search_title),
                        subtitle = stringResource(R.string.timetables_search_subtitle)
                    ) {
                        cityOptions.forEach { (slug, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        citySlug = slug
                                        viewModel.updateTimetableFromInput(slug, fromInput)
                                        viewModel.updateTimetableToInput(slug, toInput)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = citySlug == slug,
                                    onClick = {
                                        citySlug = slug
                                        viewModel.updateTimetableFromInput(slug, fromInput)
                                        viewModel.updateTimetableToInput(slug, toInput)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = fromInput,
                            onValueChange = {
                                fromInput = it
                                viewModel.updateTimetableFromInput(citySlug, it)
                            },
                            label = { Text(stringResource(R.string.timetables_from_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.isLoadingFromSuggestions) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        if (state.fromSuggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column {
                                    state.fromSuggestions.take(6).forEach { suggestion ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    fromInput = suggestion.selectedText
                                                    viewModel.selectTimetableFromSuggestion(suggestion)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(text = suggestion.selectedText, fontSize = 13.sp)
                                            if (suggestion.description.isNotBlank()) {
                                                Text(
                                                    text = suggestion.description,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = toInput,
                            onValueChange = {
                                toInput = it
                                viewModel.updateTimetableToInput(citySlug, it)
                            },
                            label = { Text(stringResource(R.string.timetables_to_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.isLoadingToSuggestions) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        if (state.toSuggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column {
                                    state.toSuggestions.take(6).forEach { suggestion ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    toInput = suggestion.selectedText
                                                    viewModel.selectTimetableToSuggestion(suggestion)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(text = suggestion.selectedText, fontSize = 13.sp)
                                            if (suggestion.description.isNotBlank()) {
                                                Text(
                                                    text = suggestion.description,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = timeInput,
                            onValueChange = {},
                            label = { Text(stringResource(R.string.timetables_time_label)) },
                            placeholder = { Text(stringResource(R.string.timetables_time_now_hint)) },
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTimePicker = true }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.timetables_time_pick_button))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.timetables_direct_only_label),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = directOnly,
                                onCheckedChange = { directOnly = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val effectiveTime = timeInput.ifBlank {
                                    val now = Calendar.getInstance()
                                    String.format(
                                        Locale.getDefault(),
                                        "%02d:%02d",
                                        now.get(Calendar.HOUR_OF_DAY),
                                        now.get(Calendar.MINUTE)
                                    )
                                }
                                timeInput = effectiveTime
                                viewModel.loadTimetables(
                                    citySlug = citySlug,
                                    fromInput = fromInput,
                                    toInput = toInput,
                                    timeInput = effectiveTime,
                                    directOnly = directOnly
                                )
                            },
                            enabled = !state.isLoading && fromInput.isNotBlank() && toInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.timetables_search_button))
                            }
                        }
                    }
                }

                if (state.errorMessage.isNotBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                state.errorMessage,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (state.connections.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.timetables_results_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    itemsIndexed(state.connections, key = { _, item -> item.id }) { index, connection ->
                        if (index == state.connections.lastIndex && state.canLoadMore && !state.isLoading && !state.isLoadingMore) {
                            LaunchedEffect(connection.id, state.connections.size, state.canLoadMore) {
                                viewModel.loadMoreTimetables()
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (connection.isDirect) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${connection.departureTime} -> ${connection.arrivalTime}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        connection.totalDuration,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                connection.segments.forEach { segment ->
                                    Text(
                                        segment.line.ifBlank { "-" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${segment.departureStop} (${segment.departureTime}) -> ${segment.arrivalStop} (${segment.arrivalTime})",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (segment.operatorName.isNotBlank()) {
                                        Text(
                                            segment.operatorName,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }

                    if (state.isLoadingMore) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    historyState: CardHistoryState,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    BackHandler(onBack = onBack)

    AppBackground(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigation = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.history_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !historyState.isLoading) {
                        Text(stringResource(R.string.refresh_button))
                    }
                }
            )

            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                when {
                    historyState.isLoading && historyState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    historyState.errorMessage.isNotBlank() && historyState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                historyState.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                    historyState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.history_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            historyState.items.forEach { item ->
                                val isPositive = item.amountText.trim().startsWith("+")
                                val isNegative = item.amountText.trim().startsWith("-")
                                val cardContainerColor = when {
                                    item.sourceType == HistorySourceType.TRANSACTION -> MaterialTheme.colorScheme.secondaryContainer
                                    isPositive -> MaterialTheme.colorScheme.tertiaryContainer
                                    isNegative -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                val amountColor = when {
                                    isPositive -> Color(0xFF1B7F3B)
                                    isNegative -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                val titleColor = when {
                                    item.sourceType == HistorySourceType.TRANSACTION -> MaterialTheme.colorScheme.onSecondaryContainer
                                    isPositive -> MaterialTheme.colorScheme.onTertiaryContainer
                                    isNegative -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = cardContainerColor
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            formatDateTime(item.timestampMs, stringResource(R.string.date_unknown)),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            item.title,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            color = titleColor
                                        )
                                        if (item.subtitle.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                item.subtitle,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (item.amountText.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                item.amountText,
                                                fontSize = 13.sp,
                                                color = amountColor,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TokenInfoDialog(qrState: QRState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_info_title)) },
        text = {
            if (qrState.tokenHex.isEmpty()) {
                Text(
                    stringResource(R.string.token_waiting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                Column {
                    SelectableText(
                        stringResource(R.string.token_hex_label, qrState.tokenHex),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SelectableText(
                        stringResource(R.string.token_b64_label, qrState.tokenBase64),
                        fontSize = 10.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logout_title)) },
        text = { Text(stringResource(R.string.logout_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.logout_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatTime(timestampMs: Long, neverLabel: String): String {
    return if (timestampMs == 0L) {
        neverLabel
    } else {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestampMs))
    }
}

private fun formatDate(timestampSeconds: Long, unknownLabel: String): String {
    if (timestampSeconds <= 0L) return unknownLabel
    val timestampMs = if (timestampSeconds < 10_000_000_000L) {
        timestampSeconds * 1000L
    } else {
        timestampSeconds
    }
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

private fun formatDateTime(timestampMs: Long, unknownLabel: String): String {
    if (timestampMs <= 0L) return unknownLabel
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
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
            .height(60.dp),
        color = Color.Transparent
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

@Composable
fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingsItemCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
fun StatusChip(text: String, isActive: Boolean) {
    val container = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(content, CircleShape)
            )
            Text(text, fontSize = 12.sp, color = content)
        }
    }
}

@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val gradient = Brush.verticalGradient(
        listOf(
            scheme.background,
            scheme.surface,
            scheme.background
        )
    )
    val backgroundBrush = if (scheme.background == Color(0xFF000000)) {
        Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF000000)))
    } else {
        gradient
    }
    Box(
        modifier = modifier.background(backgroundBrush)
    ) {
        content()
    }
}
