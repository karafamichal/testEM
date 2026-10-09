package com.ksjd.testem.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * The followed bus as a Wear OS Ongoing Activity: its icon and countdown on the watch face and in recents,
 * ticking on the watch itself between the phone's updates; a tap opens the watch app.
 */
object OngoingFollow {
    private const val CHANNEL = "follow"
    private const val ID = 4200

    fun show(context: Context, f: WatchFollow) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_follow), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(R.string.line_to, f.line, f.destination)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(listOf(countdown(context, f.targetMs, System.currentTimeMillis()), f.caption).filter { it.isNotBlank() }.joinToString(" "))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
        // The timer counts down by itself (elapsed-realtime base), so the watch face stays right between updates.
        val zero = SystemClock.elapsedRealtime() + (f.targetMs - System.currentTimeMillis())
        val status = Status.Builder()
            .addTemplate("#line# #time#")
            .addPart("line", Status.TextPart(f.line))
            .addPart("time", Status.TimerPart(zero))
            .build()
        OngoingActivity.Builder(context, ID, builder)
            .setStaticIcon(R.drawable.ic_stat_bus)
            .setTouchIntent(open)
            .setStatus(status)
            .build()
            .apply(context)
        runCatching { NotificationManagerCompat.from(context).notify(ID, builder.build()) } // no permission yet: nothing shown
    }

    fun hide(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /**
     * "Get off at the next stop", announced by the watch itself with its own vibration (three long pulses), so it's
     * known without looking even on a crowded bus. The phone keeps this alert to itself, so it comes once.
     */
    fun alertGetOff(context: Context, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GET_OFF, context.getString(R.string.channel_get_off), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = GET_OFF_PATTERN
            }
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GET_OFF)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(GET_OFF_PATTERN)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID + 1, notification) }
    }

    private const val CHANNEL_GET_OFF = "get_off"
    private val GET_OFF_PATTERN = longArrayOf(0, 700, 200, 700, 200, 700)
}
