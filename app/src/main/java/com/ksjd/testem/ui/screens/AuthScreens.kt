package com.ksjd.testem.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.ksjd.testem.Wallet
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ksjd.testem.AppState
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.R
import com.ksjd.testem.ui.components.Notice
import com.ksjd.testem.ui.components.NoticeTone

/** Shared frame for the sign-in screens: signage stripe, left-aligned copy. */
@Composable
private fun AuthFrame(
    title: String,
    body: String,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        // Stop-sign stripe: the app's one piece of ornament on these screens.
        Box(
            Modifier
                .width(56.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        content()
        Spacer(Modifier.height(24.dp))
        footer()
    }
}

/** The eye on password and PIN fields: show what is typed, or hide it again. */
@Composable
internal fun RevealButton(shown: Boolean, pin: Boolean = true, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(
                if (pin) (if (shown) R.string.pin_hide else R.string.pin_show)
                else (if (shown) R.string.password_hide else R.string.password_show)
            )
        )
    }
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String, imeAction: ImeAction, onIme: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.headlineSmall,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { RevealButton(show) { show = !show } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() })
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text)
        }
    }
}

/** Right after an account is added: its ticket gets a PIN. Cancelling removes the account again. */
@Composable
fun PinSetupScreen(viewModel: AppViewModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val tooShort = stringResource(R.string.pin_error_too_short)
    val mismatch = stringResource(R.string.pin_error_mismatch)
    val submit = {
        error = when {
            pin.length < 4 -> tooShort
            pin != confirm -> mismatch
            else -> ""
        }
        if (error.isEmpty()) viewModel.setPin(pin)
    }
    BackHandler(onBack = viewModel::removeAllAccounts)

    AuthFrame(
        title = stringResource(R.string.pin_setup_title),
        body = stringResource(R.string.pin_setup_subtitle),
        footer = {
            TextButton(onClick = viewModel::removeAllAccounts, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pin_setup_cancel))
            }
        }
    ) {
        PinField(pin, { pin = it }, stringResource(R.string.pin_label_range), ImeAction.Next) {
            focus.moveFocus(FocusDirection.Down)
        }
        Spacer(Modifier.height(12.dp))
        PinField(confirm, { confirm = it }, stringResource(R.string.pin_label_confirm), ImeAction.Done, submit)
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Notice(error, tone = NoticeTone.Error)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.pin_save_button), submit, enabled = pin.length >= 4 && confirm.length >= 4)
    }
}

/**
 * PIN or biometrics to show the ticket and account. The rest of the app never needs it,
 * so this is a dialog over whatever the user was looking at.
 */
@Composable
fun UnlockDialog(viewModel: AppViewModel, appState: AppState, activity: FragmentActivity, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val incorrect = stringResource(R.string.pin_error_incorrect)
    val lockedText = stringResource(R.string.pin_locked_out)
    val done by rememberUpdatedState(onDismiss)
    fun lockedMessage(until: Long) = lockedText.format(((until - System.currentTimeMillis()) / 1000L + 1).coerceAtLeast(1))
    val submit = {
        val lockedUntil = viewModel.pinLockedUntil()
        when {
            lockedUntil > 0 -> error = lockedMessage(lockedUntil)
            viewModel.verifyPin(pin) -> { error = ""; done() }
            else -> {
                val until = viewModel.pinLockedUntil()
                error = if (until > 0) lockedMessage(until) else incorrect
                pin = ""
            }
        }
    }
    val canUseBiometrics = appState.biometricEnabled && biometricsAvailable(activity)
    val prompt = rememberBiometricPrompt(
        activity = activity,
        onSuccess = { viewModel.unlockWithBiometrics(); done() },
        onError = { error = it },
        failedMessage = stringResource(R.string.biometric_not_recognized)
    )
    val promptInfo = biometricPromptInfo()
    // Offer the fingerprint/face prompt straight away; PIN stays as the fallback.
    LaunchedEffect(Unit) { if (canUseBiometrics) prompt.authenticate(promptInfo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_title)) },
        text = {
            Column {
                Text(stringResource(R.string.unlock_subtitle), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                PinField(pin, { pin = it }, stringResource(R.string.pin_label), ImeAction.Done) { if (pin.length >= 4) submit() }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (canUseBiometrics) {
                    TextButton(onClick = { prompt.authenticate(promptInfo) }) { Text(stringResource(R.string.use_biometrics)) }
                }
            }
        },
        confirmButton = { Button(onClick = submit, enabled = pin.length >= 4) { Text(stringResource(R.string.unlock_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** In place of a tab whose content is behind the PIN (History). */
@Composable
fun LockedScreen(onUnlock: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.unlock_button)) }
    }
}

/** The carrier's mark on account rows and ticket passes: SAD Zvolen in the app's colour, IDS BBSK in its own red. */
@Composable
internal fun walletColor(wallet: Wallet): Color =
    if (wallet == Wallet.IDS) IdsRed else MaterialTheme.colorScheme.primary

internal val IdsRed = Color(0xFFED1C24)
internal val IdsCharcoal = Color(0xFF31323C)

@Composable
internal fun walletName(wallet: Wallet): String =
    stringResource(if (wallet == Wallet.IDS) R.string.wallet_ids else R.string.wallet_sadzv)

/**
 * First run: the two accounts whose tickets the app can show, each optional (EM-0023). Adding one opens its
 * sign-in form and comes back here; Continue goes on with whatever was added (or none).
 */
@Composable
fun WelcomeScreen(viewModel: AppViewModel, appState: AppState) {
    var adding by rememberSaveable { mutableStateOf<Wallet?>(null) }
    LaunchedEffect(appState.hasAccount, appState.hasIdsAccount) { adding = null }
    adding?.let { wallet ->
        val close = { adding = null; viewModel.clearLoginError() }
        BackHandler(onBack = close)
        LoginScreen(viewModel, appState, wallet, welcome = true, onSkip = close)
        return
    }
    AuthFrame(
        title = stringResource(R.string.welcome_title),
        body = stringResource(R.string.welcome_body),
        footer = {
            if (appState.anyAccount) {
                PrimaryButton(stringResource(R.string.welcome_continue), viewModel::skipOnboarding)
            } else {
                OutlinedButton(
                    onClick = viewModel::skipOnboarding,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.welcome_skip)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_skip_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    ) {
        Text(stringResource(R.string.welcome_account_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        WalletRow(Wallet.SADZV, appState.email.takeIf { appState.hasAccount }) { adding = Wallet.SADZV }
        Spacer(Modifier.height(10.dp))
        WalletRow(Wallet.IDS, appState.idsEmail.takeIf { appState.hasIdsAccount }) { adding = Wallet.IDS }
    }
}

/** One account: what it covers, and Add, or the email it was added with. */
@Composable
internal fun WalletRow(wallet: Wallet, addedEmail: String?, onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(walletColor(wallet)))
            Column(Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp)) {
                Text(walletName(wallet), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (wallet == Wallet.IDS) R.string.wallet_ids_covers else R.string.wallet_sadzv_covers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (addedEmail != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(addedEmail, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (addedEmail == null) {
                FilledTonalButton(onClick = onAdd, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 12.dp)) {
                    Text(stringResource(R.string.welcome_add))
                }
            }
        }
    }
}

/** Both accounts use different emails: the one picked fills in reports and suggestions. */
@Composable
fun PrimaryEmailDialog(appState: AppState, onDismiss: (() -> Unit)? = null, onPick: (String) -> Unit) {
    var picked by remember { mutableStateOf(appState.primaryAccount.ifEmpty { "sadzv" }) }
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(stringResource(R.string.primary_title)) },
        text = {
            Column {
                Text(stringResource(R.string.primary_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                listOf("sadzv" to appState.email, "ids" to appState.idsEmail).forEach { (key, email) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = key }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = picked == key, onClick = { picked = key })
                        Column {
                            Text(email, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                walletName(if (key == "ids") Wallet.IDS else Wallet.SADZV),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(picked) }) { Text(stringResource(R.string.primary_use)) } },
        dismissButton = onDismiss?.let { { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } } }
    )
}

/** Adding the [wallet] account. [welcome]: from the first-run screen; [onSkip] goes back (or cancels). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(viewModel: AppViewModel, appState: AppState, wallet: Wallet, welcome: Boolean, onSkip: () -> Unit) {
    val known = if (wallet == Wallet.IDS) appState.idsEmail else appState.email
    var email by rememberSaveable(known) { mutableStateOf(known) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canSubmit = !appState.isLoading && email.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            focus.clearFocus()
            viewModel.login(email, password, wallet)
        }
    }

    AuthFrame(
        title = walletName(wallet),
        body = stringResource(if (wallet == Wallet.IDS) R.string.login_subtitle_ids else R.string.login_subtitle),
        footer = {
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (welcome) R.string.back else R.string.cancel))
            }
        }
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(listOf(AutofillType.EmailAddress, AutofillType.Username)) { email = it },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(listOf(AutofillType.Password)) { password = it },
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (showPassword) R.string.password_hide else R.string.password_show)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        if (appState.loginError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Notice(appState.loginError, tone = NoticeTone.Error)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.login_button), submit, enabled = canSubmit, loading = appState.isLoading)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.login_unofficial_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun biometricsAvailable(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS

@Composable
private fun biometricPromptInfo(): BiometricPrompt.PromptInfo {
    val title = stringResource(R.string.unlock_prompt_title)
    val subtitle = stringResource(R.string.unlock_prompt_subtitle)
    val negative = stringResource(R.string.unlock_prompt_use_pin)
    return remember(title, subtitle, negative) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .build()
    }
}

@Composable
fun rememberBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    failedMessage: String
): BiometricPrompt {
    val success by rememberUpdatedState(onSuccess)
    val error by rememberUpdatedState(onError)
    val failed by rememberUpdatedState(failedMessage)
    return remember(activity) {
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelling to type the PIN instead is not an error worth showing.
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) error(errString.toString())
                }
                override fun onAuthenticationFailed() = error(failed)
            }
        )
    }
}

/** Lets password managers fill this field (Compose 1.7 autofill API). */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.autofill(types: List<AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val fill by rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = types, onFill = { fill(it) }) }
    DisposableEffect(node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            if (state.isFocused) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node)
        }
}
