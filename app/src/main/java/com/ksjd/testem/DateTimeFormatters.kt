package com.ksjd.testem

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}
private val dateFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
}
private val dateTimeFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}

fun formatTime(timestampMs: Long, neverLabel: String): String {
    return if (timestampMs == 0L) neverLabel else timeFormatter.get()!!.format(Date(timestampMs))
}

fun formatDate(timestampSeconds: Long, unknownLabel: String): String {
    if (timestampSeconds <= 0L) return unknownLabel
    val timestampMs = if (timestampSeconds < 10_000_000_000L) timestampSeconds * 1000L else timestampSeconds
    return dateFormatter.get()!!.format(Date(timestampMs))
}

fun formatDateTime(timestampMs: Long, unknownLabel: String): String {
    if (timestampMs <= 0L) return unknownLabel
    return dateTimeFormatter.get()!!.format(Date(timestampMs))
}
