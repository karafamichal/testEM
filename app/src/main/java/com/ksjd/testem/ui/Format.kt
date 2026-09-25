package com.ksjd.testem.ui

import android.content.Context
import com.ksjd.testem.R
import com.ksjd.testem.hub.TripHistory
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun money(amount: Double, symbol: String = "€"): String {
        val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "${nf.format(amount)} ${symbol.ifBlank { "€" }}"
    }

    fun cents(cents: Long, symbol: String = "€"): String = money(cents / 100.0, symbol)

    /** Account timestamps arrive as epoch seconds (sometimes milliseconds). */
    fun toMs(value: Long): Long = if (value in 1 until 10_000_000_000L) value * 1000L else value

    fun date(epoch: Long): String {
        if (epoch <= 0L) return "–"
        return Instant.ofEpochMilli(toMs(epoch)).atZone(zone).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    }

    fun dateTime(epochMs: Long): String {
        if (epochMs <= 0L) return "–"
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = LocalDate.now(zone)
        val datePart = if (dt.toLocalDate() == today) null else dt.toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        return listOfNotNull(datePart, dt.format(clock)).joinToString(", ")
    }

    fun clock(epochMs: Long): String =
        if (epochMs <= 0L) "–" else Instant.ofEpochMilli(epochMs).atZone(zone).format(clock)

    fun clockWithSeconds(epochMs: Long): String =
        if (epochMs <= 0L) "–" else Instant.ofEpochMilli(epochMs).atZone(zone).format(clockSeconds)

    fun secondOfDay(seconds: Int): String {
        val s = ((seconds % 86_400) + 86_400) % 86_400
        return "%02d:%02d".format(s / 3600, s % 3600 / 60)
    }

    /** Whole days from now until [epoch] (negative when in the past). */
    fun daysUntil(epoch: Long): Long {
        if (epoch <= 0L) return Long.MAX_VALUE
        val end = Instant.ofEpochMilli(toMs(epoch)).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), end)
    }

    /** "Usually ~3 min late (late 7 of the last 10 workdays)". */
    fun history(context: Context, h: TripHistory): String {
        val minutes = Math.round(h.delaySeconds / 60.0).toInt()
        val usually = when {
            minutes >= 1 -> context.getString(R.string.history_usually_late, minutes)
            minutes <= -1 -> context.getString(R.string.history_usually_early, -minutes)
            else -> context.getString(R.string.history_usually_on_time)
        }
        val days = context.getString(when (h.dayType) {
            "saturday" -> R.string.history_saturdays
            "sunday" -> R.string.history_sundays
            else -> R.string.history_workdays
        })
        return usually + " " + context.getString(R.string.history_runs, h.lateRuns, h.runs, days)
    }
}
