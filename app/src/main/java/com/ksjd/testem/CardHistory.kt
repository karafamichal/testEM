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
    val amountText: String
)

data class CardHistoryState(
    val isLoading: Boolean = false,
    val items: List<CardHistoryItem> = emptyList(),
    val errorMessage: String = "",
    val lastUpdatedMs: Long = 0L
)
