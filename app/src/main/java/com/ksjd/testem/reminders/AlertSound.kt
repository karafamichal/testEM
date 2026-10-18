package com.ksjd.testem.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.R

/**
 * 2.17.0: the sound of the app's alerts (bus arriving, get off, leave now, last bus), chosen in Reminders. Android
 * fixes a channel's sound when it's made, so each sound is its own channel ("trip_alerts_chime"); the others go.
 */
object AlertSound {
    /** id to its name; "default" is the phone's own notification sound. */
    val choices = listOf("default" to R.string.sound_default, "chime" to R.string.sound_chime, "dingdong" to R.string.sound_dingdong,
        "bell" to R.string.sound_bell, "pulse" to R.string.sound_pulse)

    private fun raw(id: String) = when (id) {
        "chime" -> R.raw.alert_chime
        "dingdong" -> R.raw.alert_dingdong
        "bell" -> R.raw.alert_bell
        "pulse" -> R.raw.alert_pulse
        else -> 0
    }

    private fun uri(context: Context, id: String): Uri? =
        raw(id).takeIf { it != 0 }?.let { Uri.parse("android.resource://${context.packageName}/$it") }

    /** The channel to post [base]'s alerts on, made with the chosen sound (the phone's default keeps the old id). */
    fun channel(context: Context, base: String, name: String): String {
        val sound = CredentialsManager(context).getAlertSound()
        val id = if (raw(sound) == 0) base else "${base}_$sound"
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return id // no channels before Android 8
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notificationChannels.filter { (it.id == base || it.id.startsWith(base + "_")) && it.id != id }
            .forEach { manager.deleteNotificationChannel(it.id) }
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            uri(context, sound)?.let {
                setSound(it, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        })
        return id
    }

    /** Plays [id] once, for choosing. */
    fun preview(context: Context, id: String) {
        runCatching { RingtoneManager.getRingtone(context, uri(context, id) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
    }
}
