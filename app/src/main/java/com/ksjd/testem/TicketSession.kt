package com.ksjd.testem

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** User-facing state of the ticket connection. */
enum class TicketPhase {
    Idle,
    Connecting,
    Active,
    Offline,
    Paused,
    NoCard,
    SignedOut
}

/** The server no longer accepts our session cookie. */
class SessionExpiredException : IOException("Session expired")

/** The server rejected the email/password. */
class LoginRejectedException : IOException("Login rejected")

/**
 * A logged-in session against sadzv.qrbus.me. Mirrors the website's own request
 * sequence (landing page → account page → AJAX login), then polls the rotating
 * QR token for the selected card.
 *
 * The polling loop is a plain suspend function; the caller owns the coroutine,
 * so cancelling the caller's job stops polling and nothing restarts itself.
 */
class TicketSession(
    baseUrl: String,
    private val username: String,
    private val password: String
) {
    interface Listener {
        fun onPhase(phase: TicketPhase)
        fun onToken(base64: String, hex: String)
        fun onAccount(snapshot: AccountSnapshot)
    }

    private val userAgent = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"
    private var sessionBaseUrl: String = baseUrl
    private val loginMutex = Mutex()
    @Volatile private var isAuthenticated = false

    /** Serial number of the card whose ticket is shown. */
    @Volatile var activeSnr: String = ""

    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { existing ->
                cookies.any {
                    it.name == existing.name && it.domain == existing.domain && it.path == existing.path
                }
            }
            store.addAll(cookies)
            // The website sets these consent cookies from JavaScript; the session
            // does not work without them.
            if (url.host == HOST && cookies.isNotEmpty()) {
                val expires = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                listOf(
                    "pisnotshowhint" to "true",
                    "piscookiewindow" to "{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}"
                ).forEach { (name, value) ->
                    if (store.none { it.name == name }) {
                        store += Cookie.Builder().name(name).value(value).domain(HOST).path("/")
                            .expiresAt(expires).build()
                    }
                }
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------------
    // Polling
    // ---------------------------------------------------------------------

    /**
     * Logs in if needed and keeps the QR token fresh until the calling coroutine
     * is cancelled. Returns only when the credentials stop working.
     */
    suspend fun pollTokens(listener: Listener) {
        var lastToken: String? = null
        var networkFailures = 0
        var authFailures = 0
        listener.onPhase(TicketPhase.Connecting)

        while (currentCoroutineContext().isActive) {
            try {
                if (!isAuthenticated) {
                    val snapshot = login()
                    listener.onAccount(snapshot)
                }
                val snr = activeSnr
                if (snr.isBlank()) {
                    listener.onPhase(TicketPhase.NoCard)
                    delay(QRDaemonConfig.POLL_INTERVAL_MS)
                    continue
                }
                val token = withContext(Dispatchers.IO) { fetchToken(snr) }
                networkFailures = 0
                authFailures = 0
                if (token == null) {
                    // Server has no token for us yet; ask again shortly.
                    delay(QRDaemonConfig.SHORT_RETRY_MS)
                    continue
                }
                if (token.first != lastToken) {
                    lastToken = token.first
                    listener.onToken(token.first, token.second)
                }
                listener.onPhase(TicketPhase.Active)
                delay(QRDaemonConfig.POLL_INTERVAL_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginRejectedException) {
                listener.onPhase(TicketPhase.SignedOut)
                return
            } catch (e: SessionExpiredException) {
                isAuthenticated = false
                authFailures += 1
                if (authFailures >= 3) {
                    listener.onPhase(TicketPhase.SignedOut)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token poll failed: ${e.javaClass.simpleName}: ${e.message}")
                networkFailures += 1
                listener.onPhase(TicketPhase.Offline)
                // 5 s, 10 s, 20 s, then every 30 s.
                val backoff = (QRDaemonConfig.RETRY_DELAY_MS shl (networkFailures - 1).coerceAtMost(3))
                    .coerceAtMost(30_000L)
                delay(backoff)
            }
        }
    }

    // ---------------------------------------------------------------------
    // One-shot requests
    // ---------------------------------------------------------------------

    /** Logs in if needed and returns fresh account data. Used by the UI and the reminder worker. */
    suspend fun fetchAccount(): AccountSnapshot = withContext(Dispatchers.IO) {
        if (!isAuthenticated) return@withContext login()
        try {
            loadAccount()
        } catch (e: SessionExpiredException) {
            isAuthenticated = false
            login()
        }
    }

    suspend fun fetchCardHistory(limit: Int): Result<List<CardHistoryItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val snr = activeSnr.trim()
            if (snr.isBlank()) throw IOException("Card serial number is not available")
            if (!isAuthenticated) login()
            val body = try {
                getJson("$sessionBaseUrl/cardapi/getCardHistory/$snr/0/$limit", "$sessionBaseUrl/account")
            } catch (e: SessionExpiredException) {
                isAuthenticated = false
                login()
                getJson("$sessionBaseUrl/cardapi/getCardHistory/$snr/0/$limit", "$sessionBaseUrl/account")
            }
            if (body.isBlank()) emptyList() else parseHistory(JsonParser.parseString(body).asJsonObject)
        }
    }

    // ---------------------------------------------------------------------
    // Login flow
    // ---------------------------------------------------------------------

    private suspend fun login(): AccountSnapshot = loginMutex.withLock {
        withContext(Dispatchers.IO) {
            // Step 1: landing page (sets base cookies, resolves redirects).
            client.newCall(
                Request.Builder().url(sessionBaseUrl).get().header("User-Agent", userAgent).build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val url = response.request.url
                sessionBaseUrl = "${url.scheme}://${url.host}"
            }

            // Step 2: account page.
            client.newCall(
                Request.Builder().url("$sessionBaseUrl/account").get()
                    .header("User-Agent", userAgent)
                    .header("Referer", sessionBaseUrl)
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            }

            // Step 3: AJAX login.
            val loginBody = FormBody.Builder()
                .add("post[login]", username)
                .add("post[password]", password)
                .build()
            val loginRequest = browserRequest("$sessionBaseUrl/accountapi/login", "$sessionBaseUrl/account/login")
                .post(loginBody)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", sessionBaseUrl)
                .withCsrf(sessionBaseUrl)
                .build()
            client.newCall(loginRequest).execute().use { response ->
                // OkHttp does not keep the WPIS session cookie on its own (domain
                // mismatch), so store it explicitly for the site host.
                response.headers("Set-Cookie").forEach { header ->
                    Regex("WPIS=([^;]+)").find(header)?.let { match ->
                        val cookie = Cookie.Builder()
                            .name("WPIS")
                            .value(match.groupValues[1])
                            .domain(HOST)
                            .path("/")
                            .secure()
                            .expiresAt(System.currentTimeMillis() + 2_592_000_000L)
                            .build()
                        cookieJar.saveFromResponse(response.request.url, listOf(cookie))
                    }
                }
                if (response.code != 200) throw IOException("Login HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val success = runCatching {
                    JsonParser.parseString(body).asJsonObject.get("success")?.asBoolean
                }.getOrNull() ?: false
                if (!success) throw LoginRejectedException()
            }

            // Step 4: the website verifies the session before loading account data.
            runCatching {
                client.newCall(browserRequest("$sessionBaseUrl/accountapi/getUId", "$sessionBaseUrl/").get().build())
                    .execute().close()
            }
            isAuthenticated = true
            loadAccount()
        }
    }

    private fun loadAccount(): AccountSnapshot {
        val body = getJson("$sessionBaseUrl/userapi/getAccountDetail", "$sessionBaseUrl/account/login")
        val snapshot = parseAccount(body)
        if (activeSnr.isBlank() || snapshot.cards.none { it.snr == activeSnr }) {
            snapshot.cards.firstOrNull { it.snr.isNotBlank() }?.let { activeSnr = it.snr }
        }
        return snapshot
    }

    private fun fetchToken(snr: String): Pair<String, String>? {
        val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken"
        val request = browserRequest(tokenUrl, "$sessionBaseUrl/account")
            .post(FormBody.Builder().add("post[serialnumber]", snr).build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Origin", sessionBaseUrl)
            .withCsrf(tokenUrl)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.request.url.encodedPath.contains("/account/login")) {
                throw SessionExpiredException()
            }
            if (response.code != 200) return null
            val json = runCatching {
                JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            }.getOrNull() ?: return null
            if (json.get("success")?.asBoolean != true) return null
            val raw = json.str("base64").ifBlank { json.str("data") }
            if (raw.isBlank()) return null
            val bytes = decodeBase64(raw) ?: return null
            if (bytes.isEmpty()) return null
            return raw to bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private fun getJson(url: String, referer: String): String {
        client.newCall(browserRequest(url, referer).get().build()).execute().use { response ->
            if (response.code == 401 || response.request.url.encodedPath.contains("/account/login")) {
                throw SessionExpiredException()
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.trimStart().startsWith("<")) throw SessionExpiredException()
            return body
        }
    }

    private fun browserRequest(url: String, referer: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Referer", referer)
            .header("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
            .header("Sec-CH-UA-Mobile", "?1")
            .header("Sec-CH-UA-Platform", "\"Android\"")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("User-Agent", userAgent)
            .header("X-Requested-With", "XMLHttpRequest")

    private fun Request.Builder.withCsrf(url: String): Request.Builder {
        val httpUrl = url.toHttpUrlOrNull() ?: return this
        val raw = cookieJar.loadForRequest(httpUrl).firstOrNull {
            it.name.equals("XSRF-TOKEN", true) || it.name.equals("CSRF-TOKEN", true) ||
                it.name.equals("csrftoken", true)
        }?.value ?: return this
        val csrf = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        return header("X-XSRF-TOKEN", csrf).header("X-CSRF-TOKEN", csrf)
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private fun parseAccount(body: String): AccountSnapshot {
        val root = JsonParser.parseString(body).asJsonObject
        val data = root.obj("data") ?: root
        val user = data.obj("wertyzUser") ?: data.obj("user") ?: data

        val cardObjects = buildList {
            listOf(user, data).forEach { source ->
                source.obj("card")?.let { add(it) }
                source.arr("cards")?.forEach { if (it.isJsonObject) add(it.asJsonObject) }
            }
        }.distinctBy { it.str("snr", "cardSnr", "cardNumber", "serialNumber").ifBlank { it.toString() } }

        val cards = cardObjects.map { card ->
            val ticket = card.arr("tickets")?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                ?.let { tickets -> tickets.firstOrNull { it.get("active")?.asBoolean == true } ?: tickets.firstOrNull() }
            AccountDetails(
                snr = card.str("snr", "cardSnr", "cardSNR", "cardNumber", "cardnumber", "serialNumber", "serialnumber"),
                cardTypeName = card.str("cardTypeName", "typeName", "cardType"),
                organizationName = card.str("organizationName", "organization", "companyName"),
                cardValidFrom = card.long("validFrom", "cardValidFrom"),
                cardValidTo = card.long("validTo", "cardValidTo"),
                ticketValidFrom = ticket?.long("timeValidityFrom", "validFrom") ?: 0L,
                ticketValidTo = ticket?.long("timeValidityTo", "validTo") ?: 0L,
                discountValidFrom = card.long("discountValidFrom"),
                discountValidTo = card.long("discountValidTo"),
                creditLastBalance = card.money("creditLastBalance", "credit"),
                currencySymbol = card.str("currencySymbol", "currency").ifBlank { "€" },
                cardTemplateBase64 = card.str("base64", "cardBase64").ifBlank {
                    extractTemplateBase64(card.str("template"))
                }
            )
        }

        val firstCard = cardObjects.firstOrNull()
        val name = listOf(
            firstCard?.str("fullName", "fullname", "ownerFullName", "name").orEmpty(),
            user.str("fullName", "fullname", "name"),
            listOf(
                firstCard?.str("ownerFirstName", "firstName", "firstname").orEmpty(),
                firstCard?.str("ownerLastName", "lastName", "lastname").orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" "),
            listOf(user.str("firstName", "firstname"), user.str("lastName", "lastname"))
                .filter { it.isNotBlank() }.joinToString(" ")
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        return AccountSnapshot(userName = name, cards = cards)
    }

    private fun extractTemplateBase64(templateRaw: String): String {
        if (templateRaw.isBlank()) return ""
        val cleaned = templateRaw.replace("\\\\", "\\").replace("\\\"", "\"")
        return runCatching { JsonParser.parseString(cleaned).asJsonObject.str("base64") }
            .getOrNull()
            ?: Regex("base64\\\\\":\\\\\"([^\\\\\"]+)").find(templateRaw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun parseHistory(root: JsonObject): List<CardHistoryItem> {
        // The current qrbus API has no currency field; balances are EUR cents.
        val currency = "€"
        val result = mutableListOf<CardHistoryItem>()

        root.arr("tickets")?.forEachIndexed { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            // Current API nests data under payment/tariff/place; fall back to the
            // older flat field names for safety.
            val payment = obj.obj("payment")
            val balance = payment?.obj("balance")
            val tariff = obj.obj("tariff")
            val tariffType = tariff?.obj("type")
            val stop = obj.obj("place")?.obj("stop")

            val saleTimeSec = payment?.longOrNull("saleTime") ?: obj.longOrNull("saleTime") ?: 0L
            val oldBalance = balance?.longOrNull("oldValue") ?: obj.longOrNull("oldBalance")
            val newBalance = balance?.longOrNull("newValue") ?: obj.longOrNull("newBalance")
            val priceCents = payment?.longOrNull("price") ?: obj.longOrNull("price") ?: 0L
            val operationType = obj.longOrNull("operationType") ?: 0L
            val tariffTypeId = tariffType?.longOrNull("id") ?: obj.longOrNull("ticketTypeId") ?: 0L
            // A top-up is operationType 6 / tariff type 3 ("Vklad na kartu").
            val isTopUp = operationType == 6L || tariffTypeId == 3L

            val deltaCents = if (oldBalance != null && newBalance != null) {
                newBalance - oldBalance
            } else if (isTopUp || priceCents < 0) {
                abs(priceCents)
            } else {
                -abs(priceCents)
            }
            val stopName = stop?.str("name").orEmpty()
            val balancePart = if (oldBalance != null && newBalance != null) {
                "${formatAmount(oldBalance, currency)} → ${formatAmount(newBalance, currency)}"
            } else ""

            result += CardHistoryItem(
                id = obj.str("ticketSnr", "ticketSNR").ifBlank { "ticket-$saleTimeSec-$index" },
                sourceType = HistorySourceType.TICKET,
                timestampMs = saleTimeSec * 1000L,
                title = tariff?.str("name").orEmpty()
                    .ifBlank { tariffType?.str("name").orEmpty() }
                    .ifBlank { obj.str("ticketTypeName") }
                    .ifBlank { "Ticket" },
                subtitle = listOf(stopName, balancePart).filter { it.isNotBlank() }.joinToString("  ·  "),
                amountText = signedAmount(deltaCents, currency),
                amountCents = deltaCents,
                stopName = stopName,
                isTopUp = isTopUp || deltaCents > 0
            )
        }

        root.arr("transactions")?.forEachIndexed { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val createdAtSec = obj.longOrNull("createdAt") ?: obj.longOrNull("createTime") ?: 0L
            val type = obj.longOrNull("transactionType") ?: 0L
            val payment = obj.obj("payment")
            val balance = payment?.obj("balance")
            val oldBalance = balance?.longOrNull("oldValue")
            val newBalance = balance?.longOrNull("newValue")
            val price = payment?.longOrNull("price")

            val details = mutableListOf<String>()
            if (oldBalance != null && newBalance != null) {
                details += "${formatAmount(oldBalance, currency)} → ${formatAmount(newBalance, currency)}"
            }
            obj.arr("changes")?.forEach { change ->
                val c = change.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val field = when (c.longOrNull("editTypeId")) {
                    7L -> "Identification"
                    13L -> "Validity"
                    else -> "Change ${c.longOrNull("editTypeId") ?: 0}"
                }
                val before = c.str("valueBefore")
                val after = c.str("valueAfter")
                val value = c.str("value")
                when {
                    before.isNotBlank() && after.isNotBlank() -> details += "$field: $before → $after"
                    after.isNotBlank() -> details += "$field: $after"
                    before.isNotBlank() -> details += "$field: $before"
                    value.isNotBlank() -> details += "$field: $value"
                }
            }
            val delta = when {
                oldBalance != null && newBalance != null -> newBalance - oldBalance
                price != null && price != 0L -> -price
                else -> null
            }
            result += CardHistoryItem(
                id = "transaction-$createdAtSec-$index",
                sourceType = HistorySourceType.TRANSACTION,
                timestampMs = createdAtSec * 1000L,
                title = obj.str("desc", "description").ifBlank { "Transaction #$type" },
                subtitle = details.distinct().joinToString("  ·  "),
                amountText = delta?.let { signedAmount(it, currency) }.orEmpty(),
                amountCents = delta,
                isTopUp = (delta ?: 0L) > 0
            )
        }

        return result.sortedByDescending { it.timestampMs }
    }

    private fun signedAmount(cents: Long, currency: String): String =
        (if (cents >= 0) "+" else "−") + formatAmount(abs(cents), currency)

    private fun formatAmount(cents: Long, currency: String): String =
        String.format(Locale.US, "%.2f %s", cents / 100.0, currency)

    private fun decodeBase64(raw: String): ByteArray? {
        val stripped = raw.replace(' ', '+').replace(Regex("[^A-Za-z0-9+/=_-]"), "").trim()
        val urlSafe = stripped.contains('-') || stripped.contains('_')
        val padded = stripped + "=".repeat((4 - stripped.length % 4) % 4)
        val flags = if (urlSafe) Base64.URL_SAFE or Base64.NO_WRAP else Base64.NO_WRAP
        return runCatching { Base64.decode(padded, flags) }.getOrNull()
    }

    companion object {
        private const val TAG = "TicketSession"
        private const val HOST = "sadzv.qrbus.me"
    }
}

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.str(vararg keys: String): String {
    for (key in keys) {
        val value = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (value.isNotEmpty()) return value
    }
    return ""
}

private fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.long(vararg keys: String): Long {
    for (key in keys) longOrNull(key)?.let { return it }
    return 0L
}

/** Credit is sent either as cents (integer) or as a decimal euro string. */
private fun JsonObject.money(vararg keys: String): Double? {
    for (key in keys) {
        val prim = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: continue
        if (prim.isString) {
            val normalized = prim.asString.trim().replace(",", ".")
            val parsed = normalized.toDoubleOrNull() ?: continue
            return if (normalized.contains(".")) parsed else parsed / 100.0
        }
        if (prim.isNumber) {
            val parsed = runCatching { prim.asDouble }.getOrNull() ?: continue
            return if (parsed % 1.0 == 0.0) parsed / 100.0 else parsed
        }
    }
    return null
}
