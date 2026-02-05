package com.ksjd.testem

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ksjd.testem.ui.theme.TestEMTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.unit.TextUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TestEMTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: QRDaemonViewModel = viewModel()
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
        QRDaemonScreen(viewModel, qrState, appState)
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
fun QRDaemonScreen(viewModel: QRDaemonViewModel, qrState: QRState, appState: AppState) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    if (showLogoutDialog) {
        LogoutDialog(
            onConfirm = {
                viewModel.logout()
                showLogoutDialog = false
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
            title = {
                val name = qrState.userName.ifBlank { "testEM" }
                Text(name)
            },
            actions = {
                Button(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Logout")
                }
            }
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusCard(qrState)
            Spacer(modifier = Modifier.height(16.dp))
            
            QRCodeDisplay(qrState)
            Spacer(modifier = Modifier.height(16.dp))

            AccountInfoCard(qrState, appState)
            Spacer(modifier = Modifier.height(16.dp))
            
            ControlButtonsRow(qrState) { isPolling ->
                if (isPolling) {
                    viewModel.stopPolling()
                } else {
                    viewModel.startPolling()
                }
            }
            
            if (qrState.errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
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
        }
    }
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
                Image(
                    bitmap = qrState.qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(256.dp)
                )
            } else {
                PlaceholderQRCode()
            }
        }
    }
}

@Composable
fun PlaceholderQRCode() {
    Box(
        modifier = Modifier
            .size(256.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "No Token",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AccountInfoCard(qrState: QRState, appState: AppState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Account",
                modifier = Modifier.padding(bottom = 12.dp),
                fontWeight = FontWeight.Bold
            )

            Text("Email: ${appState.email}", fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Serial: ${appState.serialNumber}", fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Last Update: ${formatTime(qrState.lastUpdateTime)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
fun TopAppBar(title: @Composable () -> Unit, actions: @Composable () -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            title()
            actions()
        }
    }
}