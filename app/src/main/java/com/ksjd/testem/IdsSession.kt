package com.ksjd.testem

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** An IDS BBSK identifier: a chip card, ISIC, bank card or the virtual card (the one with a QR code). */
data class IdsIdentifier(
    val number: Int,
    val alias: String,
    val cardNumber: String,
    /** "Platné lístky: 1", "Kredit aktívny", "Žiadne produkty", as the e-shop says it. */
    val products: List<String>,
    val hasProducts: Boolean,
    val valid: Boolean,
    /** id_token of the virtual card's QR page; null for cards without a QR. */
    val qrId: String?
)

data class IdsTicket(
    val name: String,
    val valid: Boolean,
    val validFromMs: Long,
    val validToMs: Long,
    /** "identifikátor č. 5 (NFC Karta) 01C55703" */
    val identifier: String,
    val identifierNumber: Int,
    val price: String
)

data class IdsTopUp(val id: String, val timestampMs: Long, val title: String, val status: String, val amountCents: Long?)

data class IdsAccount(
    val email: String = "",
    /** The rider's name from the e-shop's personal details. */
    val name: String = "",
    val credit: Double? = null,
    /** "Kredit je viazaný na identifikátor č. 1 (ISIC, 042270EAEA7680)" */
    val creditNote: String = "",
    val identifiers: List<IdsIdentifier> = emptyList(),
    val tickets: List<IdsTicket> = emptyList(),
    val topUps: List<IdsTopUp> = emptyList()
) {
    /** The identifier whose QR code is shown: the virtual IDS BBSK card. */
    val qrIdentifier: IdsIdentifier? get() = identifiers.firstOrNull { it.qrId != null }
}

/** The IDS BBSK account's history as the app shows it: credit top-ups and tickets bought, newest first. */
fun IdsAccount.historyItems(): List<CardHistoryItem> {
    val bought = tickets.filter { it.validFromMs > 0 }.map { t ->
        val cents = Regex("""(\d+),(\d{2})""").find(t.price.replace(" ", ""))?.destructured?.let { (e, c) -> e.toLong() * 100 + c.toLong() }
        CardHistoryItem(
            // A pass is money spent, not a ride: History counts it in spending, not in average fare or rides.
            id = "ids-t-${t.validFromMs}-${t.name.hashCode()}", sourceType = HistorySourceType.TRANSACTION, timestampMs = t.validFromMs,
            title = t.name, subtitle = t.identifier, amountText = t.price, amountCents = cents?.let { -it }, wallet = Wallet.IDS
        )
    }
    val credit = topUps.map { u ->
        CardHistoryItem(
            id = "ids-c-${u.id}", sourceType = HistorySourceType.TRANSACTION, timestampMs = u.timestampMs, title = u.title,
            subtitle = u.status, amountText = u.amountCents?.let { "%d,%02d €".format(it / 100, it % 100) }.orEmpty(),
            amountCents = u.amountCents, isTopUp = true, wallet = Wallet.IDS
        )
    }
    return (bought + credit).sortedByDescending { it.timestampMs }
}

/**
 * The rider's eshop.idsbbsk.sk account (IDS BBSK, report EM-0023): signs in like the e-shop's own login form
 * and reads the account pages (credit, identifiers, tickets). The e-shop has no API: the pages are HTML.
 */
class IdsSession(private val email: String, private val password: String) {
    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
            store.addAll(cookies)
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }

        @Synchronized
        fun clear() = store.clear()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val loginMutex = Mutex()
    @Volatile private var signedIn = false

    /** Signs in if needed and reads the account pages; the name page (heavy: it holds the photo) only while [knownName] is empty. */
    suspend fun fetchAccount(knownName: String = ""): IdsAccount = authed {
        val credit = page("praca-s-kreditom")
        IdsAccount(
            email = email,
            name = knownName.ifBlank { IdsParser.name(page("moj-ucet")) },
            credit = IdsParser.credit(credit),
            creditNote = IdsParser.creditNote(credit),
            identifiers = IdsParser.identifiers(page("moje-identifikatory")),
            tickets = IdsParser.tickets(page("moje-listky")),
            topUps = IdsParser.topUps(credit)
        )
    }

    /**
     * The virtual card's QR code (static, EM-0023) as stored by the app: "img:<base64>" or "text:…".
     * The e-shop's link may answer with the picture itself, or a page showing or drawing it.
     */
    suspend fun fetchQr(qrId: String): String? = authed {
        val url = "${BASE}moje-identifikatory?id_token=$qrId&action=qr"
        client.newCall(request(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            if (response.request.url.encodedPath.contains("prihlasenie")) throw SessionExpiredException()
            val body = response.body ?: return@use null
            if (body.contentType()?.type == "image") return@use "img:" + android.util.Base64.encodeToString(body.bytes(), android.util.Base64.NO_WRAP)
            val html = body.string()
            IdsParser.qr(html) ?: IdsParser.qrImageUrl(html, response.request.url)?.let(::image) ?: run {
                // ponytail: debug-only trace of an unknown QR page, drop once the page format is confirmed.
                if (BuildConfig.DEBUG) android.util.Log.d("IdsQr", IdsParser.describe(html, body.contentType().toString()))
                null
            }
        }
    }

    private fun image(url: String): String? = client.newCall(request(url).get().build()).execute().use { r ->
        val body = r.body
        if (!r.isSuccessful || body?.contentType()?.type != "image") null
        else "img:" + android.util.Base64.encodeToString(body.bytes(), android.util.Base64.NO_WRAP)
    }

    private suspend fun <T> authed(block: () -> T): T = withContext(Dispatchers.IO) {
        if (!signedIn) login()
        try {
            block()
        } catch (e: SessionExpiredException) {
            signedIn = false
            login()
            block()
        }
    }

    private suspend fun login() = loginMutex.withLock {
        // EM-0047: another request (refresh after unlocking, the QR) may have signed in while this one waited.
        if (signedIn) return@withLock
        // A fresh start: an expired session's cookies can make the shop refuse a correct password.
        cookieJar.clear()
        // The landing page sets the shop's session cookie the login then uses.
        client.newCall(request(BASE).get().build()).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
        val body = FormBody.Builder()
            .add("controller", "auth").add("email", email).add("password", password)
            .add("SubmitLogin", "true").add("ajax", "1")
            .build()
        client.newCall(request(BASE).post(body).header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Login HTTP ${response.code}")
            val json = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject }.getOrNull()
                ?: throw IOException("Login answer not understood")
            if (json.get("loginSuccess")?.asBoolean != true) {
                val said = json.get("errors")?.toString().orEmpty()
                android.util.Log.w("IdsSession", "login refused: ${json.keySet()} $said")
                // EM-0050: "confirm you're not a robot" (a CAPTCHA) isn't a wrong password.
                if (said.contains("robot", ignoreCase = true) || said.contains("captcha", ignoreCase = true)) throw IOException("IDS BBSK robot check")
                throw LoginRejectedException()
            }
        }
        signedIn = true
    }

    /** An account page; one without its content means the session ended (the shop shows the login form instead). */
    private fun page(path: String, marker: String = "my-"): String =
        client.newCall(request("$BASE$path").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            if (response.request.url.encodedPath.contains("prihlasenie") || (marker.isNotEmpty() && !html.contains("class=\"my-"))) {
                throw SessionExpiredException()
            }
            html
        }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "sk-SK,sk;q=0.9")

    companion object {
        const val BASE = "https://eshop.idsbbsk.sk/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"
    }
}

/** Reads the e-shop's account pages (their HTML as of October 2026). */
object IdsParser {
    fun name(html: String): String = listOf("firstname", "lastname").map { field ->
        Regex("""name="$field"[^>]*?value="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
    }.filter { it.isNotBlank() }.joinToString(" ")

    fun credit(html: String): Double? =
        Regex("""my-credit__credit-amount">\s*([\d\s.,]+?)\s*<""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\s"), "")?.replace(',', '.')?.toDoubleOrNull()

    fun creditNote(html: String): String =
        Regex("""(?s)my-credit__header_note">(.*?)</div>""").find(html)?.groupValues?.get(1)?.let(::text).orEmpty()

    fun identifiers(html: String): List<IdsIdentifier> = rows(html, "my-tokens__table-row").mapNotNull { row ->
        val t = text(row)
        val number = Regex("""Identifikátor č\.\s*(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        // "Platné lístky: 1" is a link to the tickets, the rest status boxes.
        val products = Regex("""Kredit aktívny|Žiadne produkty|Platné lístky:\s*\d+""").findAll(t).map { it.value }.toList()
        IdsIdentifier(
            number = number,
            alias = Regex("""token-alias-input"\s*value="([^"]*)"""").find(row)?.groupValues?.get(1)?.let(::text).orEmpty(),
            cardNumber = Regex("""Číslo karty:\s*(\S+)""").find(t)?.groupValues?.get(1).orEmpty(),
            products = products,
            hasProducts = Regex("""Platné lístky:\s*[1-9]""").containsMatchIn(t),
            valid = Regex("""\bPlatný\b""").containsMatchIn(t),
            qrId = Regex("""id_token=(\d+)&(?:amp;)?action=qr""").find(row)?.groupValues?.get(1)
        )
    }

    fun tickets(html: String): List<IdsTicket> = rows(html, "my-tickets__table-row").mapNotNull { row ->
        val t = text(row)
        val name = Regex("""(?s)my-tickets__table-highlight breakable">(.*?)</div>""").find(row)?.groupValues?.get(1)?.let(::text)
            ?: return@mapNotNull null
        val dates = Regex("""(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})\s+(\d{1,2}):(\d{2})""").findAll(t).map { m ->
            val (d, mo, y, h, mi) = m.destructured
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(LocalTime.of(h.toInt(), mi.toInt())).atZone(SK_ZONE).toInstant().toEpochMilli()
        }.toList()
        val identifier = Regex("""identifikátor č\.\s*\d+.*?(?=\s+(?:Preniesť|\d+,\d{2}\s*€))""").find(t)?.value?.trim().orEmpty()
        IdsTicket(
            name = name,
            valid = t.contains("Platný") && !t.contains("vypršala"),
            validFromMs = dates.getOrElse(0) { 0L },
            validToMs = dates.getOrElse(1) { 0L },
            identifier = identifier,
            identifierNumber = Regex("""identifikátor č\.\s*(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            // Digits only: the card number can stand right before the price ("01C55703 399,00 €").
            price = Regex("""(?<![\d\w])(\d+,\d{2})\s*€""").findAll(t).lastOrNull()?.groupValues?.get(1)?.let { "$it €" }.orEmpty()
        )
    }

    fun topUps(html: String): List<IdsTopUp> = rows(html, "my-credit__table-row").mapNotNull { row ->
        val cells = Regex("""(?s)<td class="my-credit__table-column">(.*?)(?=<td|</tr)""").findAll(row).map { text(it.groupValues[1]) }.toList()
        if (cells.size < 4) return@mapNotNull null
        val date = Regex("""(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})""").find(cells[0])?.destructured ?: return@mapNotNull null
        val (d, mo, y) = date
        IdsTopUp(
            id = Regex("""data-id-order-detail="(\d+)"""").find(row)?.groupValues?.get(1).orEmpty(),
            timestampMs = LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay(SK_ZONE).toInstant().toEpochMilli(),
            title = cells[1],
            status = cells[2],
            amountCents = Regex("""(\d+),(\d{2})""").find(cells[3])?.destructured?.let { (e, c) -> e.toLong() * 100 + c.toLong() }
        )
    }

    /**
     * The QR code on the virtual card's page: the shop's own picture ("img:<base64>"), or the code's text
     * ("text:…") when the page draws it in the browser. Null when the page shows neither.
     */
    fun qr(html: String): String? {
        Regex("""<img[^>]+src="data:image/(?:png|jpeg|gif|svg\+xml);base64,([A-Za-z0-9+/=\s]+)"""").findAll(html)
            .firstOrNull { !it.value.contains("personal_photo") }?.let { return "img:" + it.groupValues[1].replace(Regex("\\s"), "") }
        listOf(
            Regex("""data-qr(?:-code|code)?="([^"]+)""""),
            Regex("""(?:new QRCode\([^,]+,\s*\{[^}]*?text|makeCode)\s*[:(]\s*["']([^"']+)["']""")
        ).forEach { re -> re.find(html)?.let { return "text:" + text(it.groupValues[1]) } }
        return null
    }

    /** A QR picture linked from the page (not the shop's icons or the account photo). */
    fun qrImageUrl(html: String, base: HttpUrl): String? =
        Regex("""<img[^>]+src="([^"]+)"""").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }
            .firstOrNull { !it.startsWith("data:") && it.contains("qr", ignoreCase = true) && !it.endsWith(".svg") }
            ?.let { base.resolve(it)?.toString() }

    /** For the debug log: the page's shape without its content. */
    fun describe(html: String, type: String): String =
        "type=$type len=${html.length} imgs=" + Regex("""<img[^>]+src="([^"]{0,80})""").findAll(html).map { it.groupValues[1] }.toList() +
            " qr-lines=" + html.lines().filter { it.contains("qr", ignoreCase = true) }.map { it.trim().take(160) }.take(12)

    private fun rows(html: String, cls: String): List<String> =
        html.split("<tr class=\"$cls\"").drop(1).map { it.substringBefore("</tr>") }

    /** Visible text of an HTML fragment, on one line (plain Kotlin, so the parser runs in unit tests too). */
    fun text(html: String): String {
        val marks = mapOf("acute" to '́', "caron" to '̌', "circ" to '̂', "uml" to '̈',
            "grave" to '̀', "ring" to '̊', "tilde" to '̃')
        val plain = html.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&([A-Za-z])(acute|caron|circ|uml|grave|ring|tilde);")) { "${it.groupValues[1]}${marks.getValue(it.groupValues[2])}" }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFC).replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
