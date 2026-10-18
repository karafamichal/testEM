package com.ksjd.testem

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class MonthSpend(val month: YearMonth, val spentCents: Long, val trips: Int)

data class HistoryInsights(
    /** Oldest first, always [MONTHS] entries ending with the current month. */
    val months: List<MonthSpend>,
    val averageFareCents: Long?,
    val tripsLeft: Int?,
    val topStops: List<Pair<String, Int>>,
    val oldestRecordMs: Long
) {
    val thisMonth: MonthSpend get() = months.last()
    val lastMonth: MonthSpend get() = months[months.size - 2]

    companion object {
        const val MONTHS = 6

        fun from(
            items: List<CardHistoryItem>,
            balance: Double?,
            zone: ZoneId = ZoneId.systemDefault(),
            now: YearMonth = YearMonth.now(zone)
        ): HistoryInsights {
            val trips = items.filter {
                it.sourceType == HistorySourceType.TICKET && !it.isTopUp && (it.amountCents ?: 0L) < 0L
            }
            val spending = items.filter { !it.isTopUp && (it.amountCents ?: 0L) < 0L }
            fun monthOf(item: CardHistoryItem) = YearMonth.from(Instant.ofEpochMilli(item.timestampMs).atZone(zone))

            val months = (MONTHS - 1 downTo 0).map { back ->
                val month = now.minusMonths(back.toLong())
                MonthSpend(
                    month = month,
                    spentCents = spending.filter { monthOf(it) == month }.sumOf { -(it.amountCents ?: 0L) },
                    trips = trips.count { monthOf(it) == month }
                )
            }
            val recentFares = trips.sortedByDescending { it.timestampMs }.take(20).mapNotNull { it.amountCents?.let { c -> -c } }
            val average = if (recentFares.isEmpty()) null else recentFares.sum() / recentFares.size
            val tripsLeft = if (average != null && average > 0 && balance != null) {
                ((balance * 100).toLong() / average).toInt().coerceAtLeast(0)
            } else null
            val topStops = trips.map { it.stopName.trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key to it.value }
            return HistoryInsights(
                months = months,
                averageFareCents = average,
                tripsLeft = tripsLeft,
                topStops = topStops,
                oldestRecordMs = items.minOfOrNull { it.timestampMs } ?: 0L
            )
        }
    }
}

/** RFC 4180 CSV of the history list. */
/** 2.17.0: one bus ridden while following it (kept on the phone for the year in numbers). */
data class RideLog(val atMs: Long, val line: String, val from: String, val to: String, val minutes: Int)

/** 2.17.0: the rider's year in numbers: paid rides from the card's history, lines and time on board from followed rides. */
data class YearSummary(
    val year: Int,
    val trips: Int,
    val spentCents: Long,
    val minutesOnBoard: Int,
    val topLine: Pair<String, Int>?,
    val topStop: Pair<String, Int>?,
    val busiestMonth: Pair<YearMonth, Int>?
) {
    val isEmpty: Boolean get() = trips == 0 && minutesOnBoard == 0

    companion object {
        fun from(items: List<CardHistoryItem>, rides: List<RideLog>, year: Int, zone: ZoneId = ZoneId.systemDefault()): YearSummary {
            fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
            val paid = items.filter { at(it.timestampMs).year == year && !it.isTopUp && (it.amountCents ?: 0L) < 0L }
            val tickets = paid.filter { it.sourceType == HistorySourceType.TICKET }
            val followed = rides.filter { at(it.atMs).year == year }
            fun <T> top(values: List<T>) = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()
            return YearSummary(
                year = year,
                trips = maxOf(tickets.size, followed.size),
                spentCents = paid.sumOf { -(it.amountCents ?: 0L) },
                minutesOnBoard = followed.sumOf { it.minutes.coerceIn(0, 300) },
                topLine = top(followed.map { it.line }.filter { it.isNotBlank() }),
                topStop = top(tickets.map { it.stopName.trim() }.filter { it.isNotBlank() }),
                busiestMonth = top(tickets.map { YearMonth.from(at(it.timestampMs)) })
            )
        }
    }
}

fun historyToCsv(items: List<CardHistoryItem>): String = buildString {
    append("date,type,description,stop,amount_eur\r\n")
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    items.forEach { item ->
        val date = Instant.ofEpochMilli(item.timestampMs).atZone(ZoneId.systemDefault()).format(formatter)
        val type = when {
            item.isTopUp -> "top-up"
            item.sourceType == HistorySourceType.TICKET -> "ticket"
            else -> "transaction"
        }
        val amount = item.amountCents?.let { "%.2f".format(java.util.Locale.US, it / 100.0) }.orEmpty()
        append(listOf(date, type, item.title, item.stopName, amount).joinToString(",") { csvField(it) })
        append("\r\n")
    }
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
