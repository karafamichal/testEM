package com.ksjd.testem

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                visualTransformation = PasswordVisualTransformation(),
                shape = AppCardShape,
                colors = lockFieldColors()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = {
                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) confirmPin = it
                },
                label = { Text(stringResource(R.string.pin_label_confirm)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSubmit) submit()
                    }
                ),
                visualTransformation = PasswordVisualTransformation(),
                shape = AppCardShape,
                colors = lockFieldColors()
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
                shape = AppButtonShape
            ) {
                Text(stringResource(R.string.pin_save_button))
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTimetables,
                modifier = Modifier.fillMaxWidth(),
                shape = AppButtonShape
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                visualTransformation = PasswordVisualTransformation(),
                shape = AppCardShape,
                colors = lockFieldColors()
            )

            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (biometricsAvailable && appState.biometricEnabled) {
                Button(
                    onClick = { prompt.authenticate(promptInfo) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = AppButtonShape
                ) {
                    Text(stringResource(R.string.use_biometrics))
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = AppButtonShape
                ) {
                    Text(stringResource(R.string.unlock_button))
                }
            } else {
                Button(
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = AppButtonShape
                ) {
                    Text(stringResource(R.string.unlock_button))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTimetables,
                modifier = Modifier.fillMaxWidth(),
                shape = AppButtonShape
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
private fun lockFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent
)
