package com.ksjd.testem

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Operator and timetable times are Slovak local time, whatever zone the phone is set to. */
val SK_ZONE: ZoneId = ZoneId.of("Europe/Bratislava")

/** A planner connection the user starred: shown under the ticket on the home tab, followable in one tap. */
data class SavedJourney(
    val id: String,
    val route: SavedRoute,
    val connection: TimetableConnection,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    /** Departure from the first stop in epoch ms, or 0 when the timetable gave no date. */
    val departureMs: Long get() = connection.segments.firstOrNull()?.let { epochMs(it.serviceDate, it.departureTime) } ?: 0L

    /** Arrival at the last stop; the date rolls over when the clock goes backwards. */
    val arrivalMs: Long
        get() {
            val last = connection.segments.lastOrNull() ?: return 0L
            val at = epochMs(last.serviceDate, last.arrivalTime)
            return if (at in 1 until epochMs(last.serviceDate, last.departureTime)) at + DAY_MS else at
        }

    /** Same buses at the same times (starring a result twice shouldn't add it twice). */
    fun sameAs(other: TimetableConnection): Boolean =
        connection.segments.map { it.line to it.departureTime to it.serviceDate } ==
            other.segments.map { it.line to it.departureTime to it.serviceDate }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun epochMs(isoDate: String, clock: String): Long {
            val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return 0L
            val time = parseClock(clock) ?: return 0L
            return date.atTime(time).atZone(SK_ZONE).toInstant().toEpochMilli()
        }
    }
}

/** "7:25" or "07:25" → 07:25, null when it isn't a time. */
fun parseClock(text: String): LocalTime? {
    val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
    val (h, m) = match.destructured
    return runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()
}

/**
 * A ride the user takes again and again ("7:25 bus to school on weekdays"). Before each
 * departure the app finds that day's bus in the timetable and starts following it by itself.
 */
data class RecurringRide(
    val id: String,
    val route: SavedRoute,
    /** Departure from the first stop, "HH:mm". */
    val time: String,
    /** Line numbers of the saved connection, in order: that day's bus is picked by them. */
    val lines: List<String> = emptyList(),
    /** ISO days of the week (1 = Monday … 7 = Sunday); only in odd weeks when [evenDays] is set. */
    val days: Set<Int> = emptySet(),
    /** Days in even weeks (ISO week numbers) when odd and even weeks differ; null = the same every week. */
    val evenDays: Set<Int>? = null,
    /** Dates it doesn't run (ISO), e.g. holidays: they win over everything else. */
    val skipDates: List<String>? = null,
    /** Extra single dates (ISO), on top of [days]. */
    val dates: List<String> = emptyList(),
    /** Only between these dates (ISO, inclusive); null = no limit. Single [dates] ignore these. */
    val startDate: String? = null,
    val endDate: String? = null,
    /** Start following this many minutes before the departure. */
    val leadMinutes: Int = 10,
    val enabled: Boolean = true
) {
    val label: String get() = "${readableStopName(route.fromText)} → ${readableStopName(route.toText)}"

    fun runsOn(day: LocalDate): Boolean {
        if (day.toString() in skipDates.orEmpty()) return false
        if (day.toString() in dates) return true
        val weekDays = if (evenDays != null && !isOddWeek(day)) evenDays else days
        if (day.dayOfWeek.value !in weekDays) return false
        val start = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return (start == null || !day.isBefore(start)) && (end == null || !day.isAfter(end))
    }

    /** The next departure strictly after [after] whose follow start (departure − lead) is still ahead, or null. */
    fun nextDeparture(after: ZonedDateTime): ZonedDateTime? {
        if (!enabled) return null
        val clock = parseClock(time) ?: return null
        val local = after.withZoneSameInstant(SK_ZONE)
        // A year ahead is plenty; 367 cheap checks.
        return (0..366L).asSequence()
            .map { local.toLocalDate().plusDays(it) }
            .filter(::runsOn)
            .map { it.atTime(clock).atZone(SK_ZONE) }
            .firstOrNull { it.minusMinutes(leadMinutes.toLong()).isAfter(local) }
    }

    /** Some day of the week is chosen, or a date. */
    val hasDays: Boolean get() = days.isNotEmpty() || !evenDays.isNullOrEmpty() || dates.isNotEmpty()

    /** EM-0076: saved with no day: just today, or tomorrow when its time (minus the lead) has already gone. */
    fun withDefaultDay(now: ZonedDateTime): RecurringRide {
        if (hasDays) return this
        val local = now.withZoneSameInstant(SK_ZONE)
        val clock = parseClock(time) ?: return this
        val today = local.toLocalDate()
        val day = if (today.atTime(clock).atZone(SK_ZONE).minusMinutes(leadMinutes.toLong()).isAfter(local)) today else today.plusDays(1)
        return copy(dates = listOf(day.toString()))
    }

    companion object {
        val WORKDAYS = setOf(1, 2, 3, 4, 5)
        val EVERY_DAY = (1..7).toSet()

        /** ISO week numbers, as in school timetables. ponytail: a 53-week year gives two odd weeks in a row. */
        fun isOddWeek(day: LocalDate): Boolean = day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 1
    }
}

/** "Zvolen,,AS" → "Zvolen, AS" */
fun readableStopName(name: String): String =
    name.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
