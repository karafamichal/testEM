package com.ksjd.testem.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
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

    companion object {
        /** The followed bus changed: watch faces showing this complication ask again. */
        fun refresh(context: Context) = runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, CountdownComplication::class.java)).requestUpdateAll()
        }
    }
}
