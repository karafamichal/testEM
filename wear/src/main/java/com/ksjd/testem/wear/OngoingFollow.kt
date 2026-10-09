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
            .setColor(lineColor(f.line))
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

    /** The watch's own alerts, each with a vibration of its own so it's known without looking. */
    enum class Buzz(val channel: String, val title: Int, val pattern: LongArray, val id: Int) {
        /** "Get off at the next stop": three long pulses. */
        GetOff("get_off", R.string.channel_get_off, longArrayOf(0, 700, 200, 700, 200, 700), ID + 1),
        /** "Leave now" for the stop (following, or a starred journey): quick, quick, long. */
        Leave("leave", R.string.channel_leave, longArrayOf(0, 150, 120, 150, 120, 900), ID + 2)
    }

    /** Announced by the watch itself (the phone keeps these alerts to itself, so each comes once). */
    fun alert(context: Context, buzz: Buzz, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(buzz.channel, context.getString(buzz.title), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = buzz.pattern
            }
        )
        val open = PendingIntent.getActivity(
            context, buzz.id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, buzz.channel)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(buzz.pattern)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(buzz.id, notification) }
    }
}
