package com.ksjd.testem

enum class HistorySourceType {
    TICKET,
    TRANSACTION
}

data class CardHistoryItem(
    val id: String,
    val sourceType: HistorySourceType,
    val timestampMs: Long,
    val title: String,
    val subtitle: String,
    val amountText: String,
    /** Signed balance change in cents (negative = spent), null if unknown. */
    val amountCents: Long? = null,
    /** Stop where a ticket was bought, if known. */
    val stopName: String = "",
    val isTopUp: Boolean = false
)

data class CardHistoryState(
    val isLoading: Boolean = false,
    val items: List<CardHistoryItem> = emptyList(),
    val errorMessage: String = "",
    val lastUpdatedMs: Long = 0L
)
