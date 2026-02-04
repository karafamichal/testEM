package com.example.testem

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.testem.ui.theme.TestEMTheme

class MainActivity : ComponentActivity() {
    private val viewModel: QRDaemonViewModel by viewModels()
    private val TAG = "MainActivity"
    private lateinit var credentialsManager: CredentialsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        credentialsManager = CredentialsManager(this)
        
        setContent {
            TestEMTheme {
                val appState by viewModel.appState.collectAsState()
                
                if (appState.isLoggedIn) {
                    QRDaemonScreen(
                        viewModel = viewModel,
                        credentialsManager = credentialsManager
                    )
                } else {
                    LoginScreen(
                        viewModel = viewModel,
                        credentialsManager = credentialsManager
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    viewModel: QRDaemonViewModel,
    credentialsManager: CredentialsManager
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serialNumber by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    
    val appState by viewModel.appState.collectAsState()
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "QR Daemon",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "Real-time QR Token Generator",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            // Email Field
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !appState.isLoading,
                singleLine = true
            )
            
            // Password Field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                visualTransformation = if (passwordVisible) 
                    VisualTransformation.None 
                else 
                    PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !appState.isLoading
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) 
                                Icons.Filled.Visibility 
                            else 
                                Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle password"
                        )
                    }
                },
                enabled = !appState.isLoading,
                singleLine = true
            )
            
            // Serial Number Field
            OutlinedTextField(
                value = serialNumber,
                onValueChange = { serialNumber = it },
                label = { Text("Serial Number") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                enabled = !appState.isLoading,
                singleLine = true
            )
            
            // Error Message
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
                        text = appState.loginError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Login Button
            Button(
                onClick = {
                    viewModel.login(
                        email = email.trim(),
                        password = password,
                        serialNumber = serialNumber.trim()
                    )
                    if (appState.loginError.isEmpty()) {
                        credentialsManager.saveCredentials(email, password, serialNumber)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !appState.isLoading && email.isNotEmpty() && password.isNotEmpty() && serialNumber.isNotEmpty()
            ) {
                if (appState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Connect & Start Polling")
                }
            }
            
            // Info Text
            Text(
                text = "Your credentials are stored locally on your device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun QRDaemonScreen(viewModel: QRDaemonViewModel) {
    val state by viewModel.state.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("QR Daemon - Active") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Logout,
                            contentDescription = "Logout",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Card
            StatusCard(state = state)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // QR Code Display
            if (state.qrBitmap != null) {
                QRCodeDisplay(bitmap = state.qrBitmap!!)
            } else {
                PlaceholderQRCode()
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Token Information
            if (state.tokenHex.isNotEmpty()) {
                TokenInfoCard(hex = state.tokenHex, base64 = state.tokenBase64)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Control Buttons
            ControlButtonsRow(
                isPolling = state.isPolling,
                onStart = { viewModel.startPolling() },
                onStop = { viewModel.stopPolling() }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Error Display
            if (state.errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                }
            }
        }
    }
    
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Are you sure?") },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Logout")
                }
            }
        )
    }
}

@Composable
fun StatusCard(state: QRState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isPolling) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Status: ${state.statusMessage}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (state.isPolling) "🟢 Polling Active" else "🔴 Polling Inactive",
                style = MaterialTheme.typography.labelSmall
            )
            if (state.lastUpdateTime > 0) {
                val ago = (System.currentTimeMillis() - state.lastUpdateTime) / 1000
                Text(
                    text = "Last update: ${ago}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun QRCodeDisplay(bitmap: android.graphics.Bitmap) {
    Card(
        modifier = Modifier
            .size(300.dp)
            .padding(8.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun PlaceholderQRCode() {
    Card(
        modifier = Modifier
            .size(300.dp)
            .padding(8.dp)
            .background(Color.LightGray),
        colors = CardDefaults.cardColors(containerColor = Color.LightGray)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No QR Code Available")
        }
    }
}

@Composable
fun TokenInfoCard(hex: String, base64: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Token Information",
                style = MaterialTheme.typography.labelMedium
            )
            
            Text(
                text = "Hex: ${hex.take(32)}…",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                text = "Base64: ${base64.take(40)}…",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ControlButtonsRow(
    isPolling: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = !isPolling,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text("Start")
        }
        
        Button(
            onClick = onStop,
            enabled = isPolling,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text("Stop")
        }
    }
}
