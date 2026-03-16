import Foundation

enum HistorySourceType: String {
    case ticket
    case transaction
}

struct CardHistoryItem: Identifiable {
    let id: String
    let sourceType: HistorySourceType
    let timestampMs: Int64
    let title: String
    let subtitle: String
    let amountText: String
}

struct CardHistoryState {
    var isLoading = false
    var items: [CardHistoryItem] = []
    var errorMessage = ""
    var lastUpdated: Date?
}
