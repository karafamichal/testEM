package com.ksjd.testem.ui.screens

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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.Modifier
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

/** Shared frame for the pre-login screens: signage stripe, left-aligned copy. */
@Composable
private fun AuthFrame(
    title: String,
    body: String,
    onBrowse: () -> Unit,
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
        TextButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_browse_public))
        }
    }
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String, imeAction: ImeAction, onIme: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.headlineSmall,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = PasswordVisualTransformation(),
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

@Composable
fun PinSetupScreen(viewModel: AppViewModel, onBrowse: () -> Unit) {
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

    AuthFrame(
        title = stringResource(R.string.pin_setup_title),
        body = stringResource(R.string.pin_setup_subtitle),
        onBrowse = onBrowse
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

@Composable
fun PinUnlockScreen(viewModel: AppViewModel, appState: AppState, activity: FragmentActivity, onBrowse: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val incorrect = stringResource(R.string.pin_error_incorrect)
    val submit = {
        if (viewModel.verifyPin(pin)) {
            error = ""
        } else {
            error = incorrect
            pin = ""
        }
    }
    val canUseBiometrics = appState.biometricEnabled && biometricsAvailable(activity)
    val prompt = rememberBiometricPrompt(
        activity = activity,
        onSuccess = { viewModel.unlockWithBiometrics() },
        onError = { error = it },
        failedMessage = stringResource(R.string.biometric_not_recognized)
    )
    val promptInfo = biometricPromptInfo()
    // Offer the fingerprint/face prompt straight away; PIN stays as the fallback.
    LaunchedEffect(Unit) { if (canUseBiometrics) prompt.authenticate(promptInfo) }

    AuthFrame(
        title = stringResource(R.string.unlock_title),
        body = stringResource(R.string.unlock_subtitle),
        onBrowse = onBrowse
    ) {
        PinField(pin, { pin = it }, stringResource(R.string.pin_label), ImeAction.Done, submit)
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Notice(error, tone = NoticeTone.Error)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.unlock_button), submit, enabled = pin.length >= 4)
        if (canUseBiometrics) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { prompt.authenticate(promptInfo) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.use_biometrics)) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(viewModel: AppViewModel, appState: AppState, activity: FragmentActivity, onBrowse: () -> Unit) {
    var email by rememberSaveable(appState.email) { mutableStateOf(appState.email) }
    var password by rememberSaveable(appState.password) { mutableStateOf(appState.password) }
    var showPassword by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val requireBiometric = appState.biometricEnabled && biometricsAvailable(activity)
    val prompt = rememberBiometricPrompt(
        activity = activity,
        onSuccess = {
            biometricError = ""
            viewModel.login(email, password)
        },
        onError = { biometricError = it },
        failedMessage = stringResource(R.string.biometric_not_recognized)
    )
    val promptInfo = biometricPromptInfo()
    val canSubmit = !appState.isLoading && email.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            focus.clearFocus()
            if (requireBiometric) prompt.authenticate(promptInfo) else viewModel.login(email, password)
        }
    }

    AuthFrame(
        title = stringResource(R.string.login_title),
        body = stringResource(R.string.login_subtitle),
        onBrowse = onBrowse
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
        val message = appState.loginError.ifBlank { biometricError }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Notice(message, tone = NoticeTone.Error)
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
