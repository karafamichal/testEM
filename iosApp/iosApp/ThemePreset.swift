import SwiftUI

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
