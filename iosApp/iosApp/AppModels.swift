import Foundation

struct LoginResponse: Decodable {
    let success: Bool
}

struct QrTokenResponse: Decodable {
    let success: Bool
    let data: String?
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
