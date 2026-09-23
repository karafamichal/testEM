package com.ksjd.testem.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ksjd.testem.AccountDetails
import com.ksjd.testem.AccountSnapshot
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.LoginRejectedException
import com.ksjd.testem.MainActivity
import com.ksjd.testem.QRDaemonConfig
import com.ksjd.testem.R
import com.ksjd.testem.TicketSession
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Low-credit and expiry notifications. */
object Reminders {
    private const val CHANNEL_ID = "account_alerts"
    private const val WORK_NAME = "account_check"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun reschedule(context: Context) {
        val prefs = CredentialsManager(context)
        val wanted = prefs.isConfigured() && (prefs.getLowCreditAlertsEnabled() || prefs.getExpiryAlertsEnabled())
        val workManager = WorkManager.getInstance(context)
        if (!wanted) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AccountCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Checks the account against the user's alert settings and posts what is due. */
    fun evaluate(context: Context, snapshot: AccountSnapshot) {
        val prefs = CredentialsManager(context)
        val now = System.currentTimeMillis()
        snapshot.cards.filter { it.snr.isNotBlank() }.forEach { card ->
            if (prefs.getLowCreditAlertsEnabled()) {
                val credit = card.creditLastBalance
                val threshold = prefs.getLowCreditWarningThreshold()
                val key = "credit-${card.snr}"
                if (credit != null && credit < threshold) {
                    if (prefs.markAlertSent(key)) {
                        notify(
                            context,
                            key.hashCode(),
                            context.getString(R.string.alert_low_credit_title),
                            context.getString(
                                R.string.alert_low_credit_body,
                                String.format(Locale.getDefault(), "%.2f", credit),
                                card.currencySymbol.ifBlank { "€" }
                            )
                        )
                    }
                } else if (credit != null) {
                    // Re-arm once the balance is topped up again.
                    prefs.clearAlertsWithPrefix(key)
                }
            }
            if (prefs.getExpiryAlertsEnabled()) {
                expiries(card).forEach { (kind, validTo) ->
                    val endMs = if (validTo < 10_000_000_000L) validTo * 1000L else validTo
                    val daysLeft = ceil((endMs - now).toDouble() / DAY_MS).toInt()
                    if (daysLeft !in 0..7) return@forEach
                    val window = if (daysLeft <= 1) 1 else 7
                    val key = "exp-$kind-${card.snr}-$validTo-$window"
                    if (!prefs.markAlertSent(key)) return@forEach
                    val what = context.getString(
                        when (kind) {
                            "card" -> R.string.alert_expiry_card
                            "ticket" -> R.string.alert_expiry_ticket
                            else -> R.string.alert_expiry_discount
                        }
                    )
                    val body = if (daysLeft <= 0) {
                        context.getString(R.string.alert_expiry_today, what)
                    } else {
                        context.resources.getQuantityString(R.plurals.alert_expiry_days, daysLeft, what, daysLeft)
                    }
                    notify(context, key.hashCode(), context.getString(R.string.alert_expiry_title), body)
                }
            }
        }
    }

    private fun expiries(card: AccountDetails): List<Pair<String, Long>> = listOf(
        "card" to card.cardValidTo,
        "ticket" to card.ticketValidTo,
        "discount" to card.discountValidTo
    ).filter { it.second > 0 }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

/** Periodically logs in with the saved account and runs [Reminders.evaluate]. */
class AccountCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = CredentialsManager(applicationContext)
        if (!prefs.isConfigured()) return Result.success()
        val session = TicketSession(QRDaemonConfig.BASE_URL, prefs.getEmail(), prefs.getPassword()).apply {
            activeSnr = prefs.getSelectedCardSnr()
        }
        return runCatching { session.fetchAccount() }.fold(
            onSuccess = { snapshot ->
                prefs.saveLastAccount(snapshot)
                Reminders.evaluate(applicationContext, snapshot)
                Result.success()
            },
            // Wrong password won't fix itself; network errors might.
            onFailure = { if (it is LoginRejectedException) Result.success() else Result.retry() }
        )
    }
}
