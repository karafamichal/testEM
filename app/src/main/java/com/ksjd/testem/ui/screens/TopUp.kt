package com.ksjd.testem.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ksjd.testem.AccountDetails
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.R
import com.ksjd.testem.TicketSession
import com.ksjd.testem.ui.Format
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** What GP webpay said when it sent the rider back (PRCODE/SRCODE 0 = paid). */
private enum class Outcome { Paid, Failed, Unknown }

/**
 * Add credit to the selected card (the website's "Vklad na kartu"). The order is made by sadzv and paid on
 * the carrier's own payment gateway (GP webpay): the money goes to the carrier, never through Plan&Ride.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TopUpSheet(viewModel: AppViewModel, card: AccountDetails, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var limits by remember { mutableStateOf<TicketSession.TopUpLimits?>(null) }
    var euros by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var gate by remember { mutableStateOf<TicketSession.PaymentGate?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val failed = stringResource(R.string.topup_error)

    LaunchedEffect(Unit) {
        runCatching { viewModel.topUpLimits() }
            .onSuccess { limits = it; euros = (it.defaultCents / 100).toString() }
            .onFailure { error = failed }
    }

    gate?.let { g ->
        PaymentPage(g, viewModel.topUpCookies()) { result ->
            gate = null
            outcome = result
            viewModel.afterTopUp()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.topup_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.topup_card, card.snr, Format.money(card.creditLastBalance ?: 0.0, card.currencySymbol)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            outcome?.let {
                Text(stringResource(when (it) {
                    Outcome.Paid -> R.string.topup_paid
                    Outcome.Failed -> R.string.topup_failed
                    Outcome.Unknown -> R.string.topup_unknown
                }), style = MaterialTheme.typography.bodyLarge,
                    color = if (it == Outcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.topup_done)) }
                return@Column
            }

            val l = limits
            if (l == null) {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            val cents = euros.toIntOrNull()?.times(100)
            val valid = cents != null && cents in l.minCents..l.maxCents
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20, 50).filter { it * 100 in l.minCents..l.maxCents }.forEach { e ->
                    FilterChip(selected = euros == e.toString(), onClick = { euros = e.toString() }, label = { Text("$e €") })
                }
            }
            OutlinedTextField(
                euros, { v -> if (v.length <= 3 && v.all(Char::isDigit)) euros = v },
                label = { Text(stringResource(R.string.topup_amount)) },
                suffix = { Text("€") },
                supportingText = { Text(stringResource(R.string.topup_range, l.minCents / 100, l.maxCents / 100)) },
                isError = euros.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.topup_who_gets_it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        runCatching { viewModel.startTopUp(l, cents!!) }
                            .onSuccess { gate = it }
                            .onFailure { error = failed }
                        busy = false
                    }
                },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) stringResource(R.string.topup_opening)
                    else stringResource(R.string.topup_pay, Format.money((cents ?: 0) / 100.0, card.currencySymbol)))
            }
        }
    }
}

/** GP webpay's page in a WebView: the form sadzv signed is posted to it, as the website does. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PaymentPage(gate: TicketSession.PaymentGate, cookies: List<Pair<String, String>>, onClose: (Outcome) -> Unit) {
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var closing by remember { mutableStateOf(false) }
    val close = { onClose(outcome ?: Outcome.Unknown) }
    // GP webpay sends the rider back to sadzv with PRCODE/SRCODE (0/0 = paid), possibly through redirects.
    fun returned(url: String) {
        val uri = Uri.parse(url)
        if (uri.host != Uri.parse(SADZV).host) return
        val pr = uri.getQueryParameter("PRCODE")
        Log.d("TopUp", "back on sadzv: ${uri.path} PRCODE=$pr SRCODE=${uri.getQueryParameter("SRCODE")}")
        outcome = when {
            pr == null -> outcome ?: Outcome.Unknown
            pr == "0" && uri.getQueryParameter("SRCODE") == "0" -> Outcome.Paid
            else -> Outcome.Failed
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.topup_gateway), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                outcome?.let {
                    Text(stringResource(if (it == Outcome.Paid) R.string.topup_paid else if (it == Outcome.Failed) R.string.topup_failed else R.string.topup_unknown),
                        color = if (it == Outcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true  // GP webpay and 3-D Secure need it
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().let { jar ->
                            jar.setAcceptCookie(true)
                            jar.setAcceptThirdPartyCookies(this, true)
                            // The return to sadzv finishes the order in this session, as in the rider's browser.
                            cookies.forEach { (name, value) -> jar.setCookie(SADZV, "$name=$value; path=/; secure") }
                        }
                        // The gateway asks with JavaScript dialogs ("Cancel the payment?"), which a WebView drops without this.
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (url.scheme == "https" || url.scheme == "http") { returned(url.toString()); return false }
                                // A bank's app for 3-D Secure; only something that can open from a browser.
                                runCatching {
                                    val intent = if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                                        else Intent(Intent.ACTION_VIEW, url)
                                    intent.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null)
                                    context.startActivity(intent)
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = returned(url)

                            // Back on sadzv: give its page a moment to finish the order, then close and show the result.
                            override fun onPageFinished(view: WebView, url: String) {
                                returned(url)
                                if (outcome != null && !closing) {
                                    closing = true
                                    view.postDelayed({ close() }, 2_500)
                                }
                            }
                        }
                        val body = gate.fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
                        postUrl(gate.url, body.toByteArray())
                    }
                })
            }
        }
    }
}

private const val SADZV = "https://sadzv.qrbus.me"
