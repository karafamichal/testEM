package com.ksjd.testem.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The followed bus on any watch face: its line and a countdown the watch face itself counts down (EM-0041).
 * Empty when nothing is followed. Tap: the watch app.
 */
class CountdownComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type != ComplicationType.SHORT_TEXT) null else shortText("34", Instant.now().plusSeconds(5 * 60))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val f = FollowStore.current(this) ?: return NoDataComplicationData()
        if (f.targetMs <= 0L) return NoDataComplicationData()
        return shortText(f.line, Instant.ofEpochMilli(f.targetMs))
    }

    private fun shortText(line: String, at: Instant): ComplicationData {
        val countdown = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(at))
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return ShortTextComplicationData.Builder(countdown, PlainComplicationText.Builder(getString(R.string.line_countdown, line)).build())
            .setTitle(PlainComplicationText.Builder(line).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bus)).build())
            .setTapAction(open)
            .build()
    }

}

private fun Context.openApp(code: Int) = PendingIntent.getActivity(this, code, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
private fun plain(text: String) = PlainComplicationText.Builder(text).build()
private fun Context.busIcon() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bus)).build()

/** Credit on the watch face: SAD Zvolen's, else IDS BBSK's (from the phone; Account → Watch can hide it). Tap: the app. */
class CreditComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type != ComplicationType.SHORT_TEXT) null else credit("SAD", 4.20)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        return home?.creditSadzv?.let { credit("SAD", it) } ?: home?.creditIds?.let { credit("IDS", it) } ?: NoDataComplicationData()
    }

    private fun credit(title: String, value: Double): ComplicationData =
        ShortTextComplicationData.Builder(plain("%.2f".format(value).replace('.', ',') + "€"), plain(getString(R.string.credit) + " " + money(value)))
            .setTitle(plain(title))
            .setTapAction(openApp(3))
            .build()
}

/** The next bus at the first favourite stop: its line and a countdown the watch face counts down itself. */
class NextDepartureComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type != ComplicationType.SHORT_TEXT) null else departure("34", Instant.now().plusSeconds(4 * 60), "")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        val board = home?.favourites?.firstOrNull() ?: return NoDataComplicationData()
        val next = board.departures.firstOrNull { it.atMs > System.currentTimeMillis() } ?: return NoDataComplicationData()
        return departure(next.line, Instant.ofEpochMilli(next.atMs), board.name)
    }

    private fun departure(line: String, at: Instant, stop: String): ComplicationData {
        val countdown = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(at))
            .setMinimumTimeUnit(TimeUnit.MINUTES).build()
        return ShortTextComplicationData.Builder(countdown, plain(getString(R.string.next_at, line, stop)))
            .setTitle(plain(line))
            .setMonochromaticImage(busIcon())
            .setTapAction(openApp(4))
            .build()
    }
}

/**
 * "Bus is here" from the watch face (the follow notification's first button: "Bus is here", then "Bus is leaving",
 * "At <stop>"): a tap presses it on the phone. Empty while there's nothing to report.
 */
class ReportComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> short("34", getString(R.string.preview_here))
        ComplicationType.LONG_TEXT -> long("34", getString(R.string.preview_here))
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val f = FollowStore.current(this)
        val label = f?.buttons?.firstOrNull() ?: return NoDataComplicationData()
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> short(f.line, label)
            ComplicationType.LONG_TEXT -> long(f.line, label)
            else -> null
        }
    }

    private fun press() = PendingIntent.getBroadcast(this, 5, Intent(this, ReportPressReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun short(line: String, label: String): ComplicationData =
        ShortTextComplicationData.Builder(plain(label), plain(label)).setTitle(plain(line)).setTapAction(press()).build()

    private fun long(line: String, label: String): ComplicationData =
        LongTextComplicationData.Builder(plain(label), plain(label)).setTitle(plain(line)).setMonochromaticImage(busIcon()).setTapAction(press()).build()
}

/** The report complication's tap: the phone presses its notification's first button; a short buzz says it went. */
class ReportPressReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        sendToPhone(context, ACTION_PATH, "0") { ok ->
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(if (ok) 60 else 400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            if (!ok) android.widget.Toast.makeText(context, R.string.report_no_phone, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
