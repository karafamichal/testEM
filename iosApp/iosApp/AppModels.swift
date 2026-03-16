import SwiftUI
import Foundation

struct LoginResponse: Decodable {
    let success: Bool
}

struct QrTokenResponse: Decodable {
    let success: Bool
    let data: String?
}

struct AccountDetails {
    var cardTypeName: String = ""
    var organizationName: String = ""
    var cardValidFrom: Int64 = 0
    var cardValidTo: Int64 = 0
    var ticketValidFrom: Int64 = 0
    var ticketValidTo: Int64 = 0
    var discountValidFrom: Int64 = 0
    var discountValidTo: Int64 = 0
    var creditLastBalance: Double?
    var currencySymbol: String = ""
    var cardTemplateBase64: String = ""
}

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

struct ThemePreset: Identifiable, Equatable {
    let id: String
    let name: String
    let primary: Color
    let secondary: Color
    let tertiary: Color
}

struct StoredThemePreset: Codable {
    let id: String
    let name: String
    let primaryHex: String
    let secondaryHex: String
    let tertiaryHex: String
}

enum SettingsPage {
    case root
    case layout
    case security
}

enum SectionId: String, CaseIterable {
    case status
    case qr
    case nfc
    case controls
    case error
}
