import SwiftUI
import Foundation
import CoreImage.CIFilterBuiltins
import LocalAuthentication
import CryptoKit
import Security
import UIKit

private struct LoginResponse: Decodable {
    let success: Bool
}

private struct QrTokenResponse: Decodable {
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

private struct StoredThemePreset: Codable {
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

@MainActor
final class TestEMViewModel: ObservableObject {
    @Published var email: String {
        didSet {
            UserDefaults.standard.set(email, forKey: prefEmail)
        }
    }
    @Published var password: String {
        didSet {
            UserDefaults.standard.set(password, forKey: prefPassword)
        }
    }
    @Published var serialNumber: String {
        didSet {
            UserDefaults.standard.set(serialNumber, forKey: prefSerial)
        }
    }

    @Published var isLoggingIn = false
    @Published var isLoggedIn = false
    @Published var isPolling = false
    @Published var nfcEnabled = false
    @Published var nfcUid = "" {
        didSet {
            UserDefaults.standard.set(nfcUid, forKey: prefNfcUid)
        }
    }
    @Published var statusMessage = "Ready"
    @Published var errorMessage = ""

    @Published var tokenBase64 = ""
    @Published var tokenHex = ""
    @Published var qrPayload = ""
    @Published var lastUpdated: Date?

    @Published var accountDetails = AccountDetails()
    @Published var historyState = CardHistoryState()

    @Published var isPinSet = false
    @Published var isAppUnlocked = true
    @Published var biometricEnabled = true
    @Published var lockTimeoutSeconds = 30
    @Published var amoledEnabled = false
    @Published var languageCode = "sk"
    @Published var themePresets: [ThemePreset] = [
        ThemePreset(
            id: "classic",
            name: "Classic",
            primary: Color(red: 0.40, green: 0.31, blue: 0.64),
            secondary: Color(red: 0.29, green: 0.55, blue: 0.69),
            tertiary: Color(red: 0.56, green: 0.43, blue: 0.76)
        ),
        ThemePreset(
            id: "ocean",
            name: "Ocean",
            primary: Color(red: 0.07, green: 0.44, blue: 0.39),
            secondary: Color(red: 0.08, green: 0.57, blue: 0.71),
            tertiary: Color(red: 0.27, green: 0.66, blue: 0.58)
        ),
        ThemePreset(
            id: "sunset",
            name: "Sunset",
            primary: Color(red: 0.91, green: 0.37, blue: 0.02),
            secondary: Color(red: 0.81, green: 0.28, blue: 0.18),
            tertiary: Color(red: 0.97, green: 0.62, blue: 0.22)
        )
    ]
    @Published var selectedThemeId = "classic"
    @Published var layoutOrder: [SectionId] = [.status, .qr, .nfc, .controls, .error]
    @Published var hiddenSections: Set<SectionId> = [.error, .nfc]

    private let baseURL = URL(string: "https://sadzv.qrbus.me")!
    private let cookieStorage = HTTPCookieStorage()
    private var pollTask: Task<Void, Never>?
    private let pollIntervalNs: UInt64 = 25_000_000_000
    private var backgroundAt: Date?
    private var isBiometricPromptInProgress = false
    private var didAttemptAutoBiometricThisForeground = false

    private let prefEmail = "testem.email"
    private let prefPassword = "testem.password"
    private let prefSerial = "testem.serial"
    private let prefNfcUid = "testem.nfcUid"
    private let prefBiometricEnabled = "testem.biometricEnabled"
    private let prefLockTimeout = "testem.lockTimeoutSeconds"
    private let prefAmoledEnabled = "testem.amoledEnabled"
    private let prefLanguageCode = "testem.languageCode"
    private let prefSelectedThemeId = "testem.selectedThemeId"
    private let prefThemePresets = "testem.themePresets"
    private let prefLayoutOrder = "testem.layoutOrder"
    private let prefHiddenSections = "testem.hiddenSections"
    private let prefPinSalt = "testem.pinSalt"
    private let prefPinHash = "testem.pinHash"

    init() {
        self.email = UserDefaults.standard.string(forKey: prefEmail) ?? ""
        self.password = UserDefaults.standard.string(forKey: prefPassword) ?? ""
        self.serialNumber = UserDefaults.standard.string(forKey: prefSerial) ?? ""
        self.nfcUid = UserDefaults.standard.string(forKey: prefNfcUid) ?? ""
        self.biometricEnabled = UserDefaults.standard.object(forKey: prefBiometricEnabled) as? Bool ?? true
        self.amoledEnabled = UserDefaults.standard.object(forKey: prefAmoledEnabled) as? Bool ?? false
        self.languageCode = UserDefaults.standard.string(forKey: prefLanguageCode) ?? "sk"
        self.selectedThemeId = UserDefaults.standard.string(forKey: prefSelectedThemeId) ?? "classic"
        if let storedPresets = loadStoredThemePresets(), !storedPresets.isEmpty {
            self.themePresets = storedPresets
        }
        let savedTimeout = UserDefaults.standard.object(forKey: prefLockTimeout) as? Int ?? 30
        self.lockTimeoutSeconds = max(0, savedTimeout)
        UserDefaults.standard.set(self.lockTimeoutSeconds, forKey: prefLockTimeout)
        if let rawOrder = UserDefaults.standard.array(forKey: prefLayoutOrder) as? [String] {
            let mapped = rawOrder.compactMap { SectionId(rawValue: $0) }
            if !mapped.isEmpty {
                self.layoutOrder = mapped
            }
        }
        if let rawHidden = UserDefaults.standard.array(forKey: prefHiddenSections) as? [String] {
            self.hiddenSections = Set(rawHidden.compactMap { SectionId(rawValue: $0) })
        }
        self.isPinSet = pinHashAndSalt() != nil
        self.isAppUnlocked = !isPinSet
    }

    func login() {
        guard isAppUnlocked else {
            errorMessage = "Unlock app first."
            return
        }
        guard !email.isEmpty, !password.isEmpty else {
            errorMessage = "Fill in email and password."
            return
        }

        isLoggingIn = true
        errorMessage = ""
        statusMessage = "Opening base URL..."

        Task {
            defer { isLoggingIn = false }
            do {
                serialNumber = ""
                try await warmSessionAndLogin()
                isLoggedIn = true
                errorMessage = ""
                statusMessage = "Login successful"
                persistCredentials()

                do {
                    try await loadAccountDetails()
                    if serialNumber.isEmpty {
                        statusMessage = "Login successful, waiting for SNR"
                    }
                } catch {
                    statusMessage = "Login successful, account details pending"
                }

                await loadCardHistory()
                startPolling()
            } catch {
                isLoggedIn = false
                errorMessage = "Login failed: \(error.localizedDescription)"
                statusMessage = "Login failed"
            }
        }
    }

    func logout() {
        stopPolling()
        clearSessionCookies()
        isLoggedIn = false
        nfcEnabled = false
        nfcUid = ""
        tokenBase64 = ""
        tokenHex = ""
        qrPayload = ""
        lastUpdated = nil
        accountDetails = AccountDetails()
        historyState = CardHistoryState()
        statusMessage = "Logged out"
        errorMessage = ""
    }

    func startPolling() {
        guard isLoggedIn else { return }
        guard !serialNumber.isEmpty else {
            statusMessage = "Loading account details to obtain serial number..."
            Task {
                do {
                    try await loadAccountDetails()
                    if serialNumber.isEmpty {
                        statusMessage = "Unable to find serial number from account details."
                        return
                    }
                    startPolling()
                } catch {
                    statusMessage = "Failed to load serial number"
                    errorMessage = "Failed to load serial number: \(error.localizedDescription)"
                }
            }
            return
        }
        if isPolling { return }
        isPolling = true
        statusMessage = "Polling started"

        pollTask = Task {
            while !Task.isCancelled {
                do {
                    try await pollOnce()
                } catch {
                    errorMessage = "Polling error: \(error.localizedDescription)"
                    statusMessage = "Polling error"
                }
                try? await Task.sleep(nanoseconds: pollIntervalNs)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
        isPolling = false
        statusMessage = "Polling stopped"
    }

    func loadCardHistory(limit: Int = 20) async {
        guard isLoggedIn else { return }
        if serialNumber.isEmpty {
            do {
                try await loadAccountDetails()
            } catch {
                historyState.isLoading = false
                historyState.errorMessage = "Failed to load serial number: \(error.localizedDescription)"
                return
            }
            if serialNumber.isEmpty {
                historyState.isLoading = false
                historyState.errorMessage = "Serial number not found in account details."
                return
            }
        }
        historyState.isLoading = true
        historyState.errorMessage = ""
        do {
            let items = try await fetchCardHistory(limit: limit)
            historyState = CardHistoryState(
                isLoading: false,
                items: items,
                errorMessage: "",
                lastUpdated: Date()
            )
        } catch {
            historyState.isLoading = false
            historyState.errorMessage = error.localizedDescription
        }
    }

    func setPin(_ pin: String) -> Bool {
        guard pin.count >= 4, pin.count <= 8, pin.allSatisfy({ $0.isNumber }) else {
            return false
        }
        var salt = Data(count: 16)
        let result = salt.withUnsafeMutableBytes { bytes -> Int32 in
            guard let baseAddress = bytes.baseAddress else { return -1 }
            return SecRandomCopyBytes(kSecRandomDefault, 16, baseAddress)
        }
        guard result == errSecSuccess else { return false }
        let hash = hashPin(pin: pin, salt: salt)
        UserDefaults.standard.set(salt.base64EncodedString(), forKey: prefPinSalt)
        UserDefaults.standard.set(hash.base64EncodedString(), forKey: prefPinHash)
        isPinSet = true
        isAppUnlocked = true
        return true
    }

    func verifyPin(_ pin: String) -> Bool {
        guard let (storedHash, salt) = pinHashAndSalt() else { return false }
        let actual = hashPin(pin: pin, salt: salt)
        let ok = actual == storedHash
        if ok {
            isAppUnlocked = true
        }
        return ok
    }

    func setBiometricEnabled(_ enabled: Bool) {
        biometricEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: prefBiometricEnabled)
    }

    func setLockTimeoutSeconds(_ seconds: Int) {
        lockTimeoutSeconds = max(0, seconds)
        UserDefaults.standard.set(lockTimeoutSeconds, forKey: prefLockTimeout)
    }

    func toggleNfc() -> String? {
        nfcEnabled.toggle()
        if nfcEnabled {
            if nfcUid.isEmpty {
                nfcUid = generateUid()
                statusMessage = "NFC mode enabled"
                return nfcUid
            }
            statusMessage = "NFC mode enabled"
        } else {
            statusMessage = "NFC mode disabled"
        }
        return nil
    }

    func setAmoledEnabled(_ enabled: Bool) {
        amoledEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: prefAmoledEnabled)
    }

    func setLanguageCode(_ code: String) {
        languageCode = code
        UserDefaults.standard.set(code, forKey: prefLanguageCode)
    }

    func selectThemePreset(_ presetId: String) {
        selectedThemeId = presetId
        UserDefaults.standard.set(presetId, forKey: prefSelectedThemeId)
    }

    func moveLayoutItem(_ id: SectionId, direction: Int) {
        guard let index = layoutOrder.firstIndex(of: id) else { return }
        let target = index + direction
        guard layoutOrder.indices.contains(target) else { return }
        var updated = layoutOrder
        let moved = updated.remove(at: index)
        updated.insert(moved, at: target)
        layoutOrder = updated
        UserDefaults.standard.set(updated.map { $0.rawValue }, forKey: prefLayoutOrder)
    }

    func setSectionHidden(_ id: SectionId, hidden: Bool) {
        if hidden {
            hiddenSections.insert(id)
        } else {
            hiddenSections.remove(id)
        }
        UserDefaults.standard.set(hiddenSections.map { $0.rawValue }, forKey: prefHiddenSections)
    }

    func changePin(currentPin: String, newPin: String) -> Bool {
        guard verifyPin(currentPin) else { return false }
        return setPin(newPin)
    }

    func addThemePreset(name: String, primary: Color, secondary: Color, tertiary: Color) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let id = "custom-\(Int(Date().timeIntervalSince1970))"
        let preset = ThemePreset(id: id, name: trimmed, primary: primary, secondary: secondary, tertiary: tertiary)
        themePresets.append(preset)
        selectedThemeId = id
        UserDefaults.standard.set(id, forKey: prefSelectedThemeId)
        saveThemePresets()
    }

    var currentAccentColor: Color {
        themePresets.first(where: { $0.id == selectedThemeId })?.primary ?? .green
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            didAttemptAutoBiometricThisForeground = false
            if isPinSet {
                backgroundAt = Date()
            }
        case .active:
            guard isPinSet else { return }
            let timeout = max(0, lockTimeoutSeconds)
            if timeout == 0 {
                isAppUnlocked = false
            } else if let bg = backgroundAt {
                if Date().timeIntervalSince(bg) >= Double(timeout) {
                    isAppUnlocked = false
                }
            }
            if biometricEnabled && !isAppUnlocked && !didAttemptAutoBiometricThisForeground {
                didAttemptAutoBiometricThisForeground = true
                Task {
                    _ = await unlockWithBiometrics(triggeredAutomatically: true)
                }
            }
        default:
            break
        }
    }

    func unlockWithBiometrics(triggeredAutomatically: Bool = false) async -> Bool {
        guard biometricEnabled else { return false }
        guard !isBiometricPromptInProgress else { return false }
        isBiometricPromptInProgress = true
        defer { isBiometricPromptInProgress = false }

        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            if let error, !triggeredAutomatically {
                self.errorMessage = "Biometric unavailable: \(error.localizedDescription)"
            }
            return false
        }

        do {
            let ok = try await context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: "Unlock testEM"
            )
            if ok {
                isAppUnlocked = true
                errorMessage = ""
                return true
            }
        } catch {
            if triggeredAutomatically,
               let laError = error as? LAError,
               laError.code == .userCancel || laError.code == .systemCancel || laError.code == .appCancel {
                return false
            }
            errorMessage = "Biometric failed: \(error.localizedDescription)"
        }
        return false
    }

    private func pinHashAndSalt() -> (Data, Data)? {
        guard
            let hashB64 = UserDefaults.standard.string(forKey: prefPinHash),
            let saltB64 = UserDefaults.standard.string(forKey: prefPinSalt),
            let hash = Data(base64Encoded: hashB64),
            let salt = Data(base64Encoded: saltB64)
        else {
            return nil
        }
        return (hash, salt)
    }

    private func hashPin(pin: String, salt: Data) -> Data {
        var input = Data()
        input.append(salt)
        input.append(Data(pin.utf8))
        let digest = SHA256.hash(data: input)
        return Data(digest)
    }

    private func persistCredentials() {
        // Values are persisted by property observers; keep this for explicit save points.
        UserDefaults.standard.set(email, forKey: prefEmail)
        UserDefaults.standard.set(password, forKey: prefPassword)
        UserDefaults.standard.set(serialNumber, forKey: prefSerial)
    }

    private func clearSessionCookies() {
        cookieStorage.cookies?.forEach { cookieStorage.deleteCookie($0) }
    }

    private func loadStoredThemePresets() -> [ThemePreset]? {
        guard let data = UserDefaults.standard.data(forKey: prefThemePresets) else {
            return nil
        }
        guard let decoded = try? JSONDecoder().decode([StoredThemePreset].self, from: data) else {
            return nil
        }
        return decoded.compactMap { stored in
            guard
                let primary = color(fromHex: stored.primaryHex),
                let secondary = color(fromHex: stored.secondaryHex),
                let tertiary = color(fromHex: stored.tertiaryHex)
            else {
                return nil
            }
            return ThemePreset(
                id: stored.id,
                name: stored.name,
                primary: primary,
                secondary: secondary,
                tertiary: tertiary
            )
        }
    }

    private func saveThemePresets() {
        let stored = themePresets.map {
            StoredThemePreset(
                id: $0.id,
                name: $0.name,
                primaryHex: hexString(from: $0.primary),
                secondaryHex: hexString(from: $0.secondary),
                tertiaryHex: hexString(from: $0.tertiary)
            )
        }
        if let data = try? JSONEncoder().encode(stored) {
            UserDefaults.standard.set(data, forKey: prefThemePresets)
        }
    }

    private func color(fromHex hex: String) -> Color? {
        let cleaned = hex.replacingOccurrences(of: "#", with: "")
        guard cleaned.count == 8, let value = UInt64(cleaned, radix: 16) else { return nil }
        let a = Double((value & 0xFF000000) >> 24) / 255.0
        let r = Double((value & 0x00FF0000) >> 16) / 255.0
        let g = Double((value & 0x0000FF00) >> 8) / 255.0
        let b = Double(value & 0x000000FF) / 255.0
        return Color(red: r, green: g, blue: b, opacity: a)
    }

    private func hexString(from color: Color) -> String {
        let uiColor = UIColor(color)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        guard uiColor.getRed(&r, green: &g, blue: &b, alpha: &a) else {
            return "FF000000"
        }
        let alpha = Int(round(a * 255))
        let red = Int(round(r * 255))
        let green = Int(round(g * 255))
        let blue = Int(round(b * 255))
        return String(format: "%02X%02X%02X%02X", alpha, red, green, blue)
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = cookieStorage
        configuration.httpShouldSetCookies = true
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration)
    }

    private func warmSessionAndLogin() async throws {
        let session = makeSession()

        statusMessage = "Opening base URL..."
        _ = try await performRequest(session: session, path: "/", method: "GET")

        statusMessage = "Opening account page..."
        _ = try await performRequest(session: session, path: "/account", method: "GET")

        statusMessage = "Submitting login..."
        let loginBody = "post[login]=\(urlEncode(email))&post[password]=\(urlEncode(password))"
        let loginData = try await performRequest(
            session: session,
            path: "/accountapi/login",
            method: "POST",
            body: loginBody,
            contentType: "application/x-www-form-urlencoded; charset=UTF-8",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )

        let decoded = try JSONDecoder().decode(LoginResponse.self, from: loginData)
        if !decoded.success {
            throw NSError(domain: "testEM", code: 401, userInfo: [NSLocalizedDescriptionKey: "Server returned success=false"])
        }
        statusMessage = "Session ready"
    }

    private func pollOnce() async throws {
        let session = makeSession()
        let tokenBody = "post[serialnumber]=\(urlEncode(serialNumber))"
        let raw = try await performRequest(
            session: session,
            path: "/cardapi/getQrToken",
            method: "POST",
            body: tokenBody,
            contentType: "application/x-www-form-urlencoded; charset=UTF-8",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )

        let tokenResponse = try JSONDecoder().decode(QrTokenResponse.self, from: raw)
        guard tokenResponse.success, let encoded = tokenResponse.data, !encoded.isEmpty else {
            throw NSError(domain: "testEM", code: 500, userInfo: [NSLocalizedDescriptionKey: "Token unavailable"])
        }

        tokenBase64 = encoded
        tokenHex = base64ToHex(encoded) ?? ""
        qrPayload = tokenHex.isEmpty ? encoded : tokenHex
        lastUpdated = Date()
        statusMessage = "Polling active"
        errorMessage = ""
    }

    func loadAccountDetails() async throws {
        let session = makeSession()
        let raw = try await performRequest(
            session: session,
            path: "/userapi/getAccountDetail",
            method: "GET",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )

        guard let root = try JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw NSError(domain: "testEM", code: 500, userInfo: [NSLocalizedDescriptionKey: "Account detail parse failed"])
        }

        let data = (root["data"] as? [String: Any]) ?? root
        let user = (data["wertyzUser"] as? [String: Any]) ?? (data["user"] as? [String: Any]) ?? data
        let card = firstCard(from: user) ?? firstCard(from: data) ?? [:]
        let ticket = firstTicket(from: card) ?? [:]

        let snr = readString(card, keys: ["snr", "cardSnr", "cardSNR", "cardNumber", "serialNumber"])
        if !snr.isEmpty {
            serialNumber = snr
            UserDefaults.standard.set(snr, forKey: prefSerial)
        }

        let templateRaw = readString(card, keys: ["template"])
        let templateBase64 = readString(card, keys: ["base64", "cardBase64"]).isEmpty
            ? extractTemplateBase64(templateRaw)
            : readString(card, keys: ["base64", "cardBase64"])

        accountDetails = AccountDetails(
            cardTypeName: readString(card, keys: ["cardTypeName", "typeName", "cardType"]),
            organizationName: readString(card, keys: ["organizationName", "organization", "companyName"]),
            cardValidFrom: readInt64(card, keys: ["validFrom", "cardValidFrom"]),
            cardValidTo: readInt64(card, keys: ["validTo", "cardValidTo"]),
            ticketValidFrom: readInt64(ticket, keys: ["timeValidityFrom", "validFrom"]),
            ticketValidTo: readInt64(ticket, keys: ["timeValidityTo", "validTo"]),
            discountValidFrom: readInt64(card, keys: ["discountValidFrom"]),
            discountValidTo: readInt64(card, keys: ["discountValidTo"]),
            creditLastBalance: readMoney(card, keys: ["creditLastBalance", "credit"]),
            currencySymbol: readString(card, keys: ["currencySymbol", "currency"]),
            cardTemplateBase64: templateBase64
        )
    }

    private func fetchCardHistory(limit: Int) async throws -> [CardHistoryItem] {
        let session = makeSession()
        let path = "/cardapi/getCardHistory/\(serialNumber)/0/\(limit)"
        let raw = try await performRequest(
            session: session,
            path: path,
            method: "GET",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )

        guard let root = try JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw NSError(domain: "testEM", code: 500, userInfo: [NSLocalizedDescriptionKey: "History parse failed"])
        }

        let tickets = root["tickets"] as? [[String: Any]] ?? []
        let transactions = root["transactions"] as? [[String: Any]] ?? []
        let currency = readString(tickets.first ?? [:], keys: ["currencySymbol"])
        var result: [CardHistoryItem] = []

        for (index, ticket) in tickets.enumerated() {
            let saleTimeSec = readInt64(ticket, keys: ["saleTime"])
            let saleTimeMs = normalizeHistoryTimestamp(saleTimeSec * 1000)
            let ticketId = readString(ticket, keys: ["ticketSNR"]).isEmpty ? "ticket-\(saleTimeMs)-\(index)" : readString(ticket, keys: ["ticketSNR"])
            let tariffName = readString(ticket, keys: ["tariffName"])
            let ticketTypeName = readString(ticket, keys: ["ticketTypeName"])
            let ticketTypeId = readInt(ticket, keys: ["ticketTypeId"])
            let priceCents = readInt64(ticket, keys: ["price"])
            let oldBalance = readOptionalInt64(ticket, keys: ["oldBalance"])
            let newBalance = readOptionalInt64(ticket, keys: ["newBalance"])
            let title = ticketTypeName.isEmpty ? "Ticket" : ticketTypeName
            let detailLabel = tariffName.isEmpty ? "Card event" : tariffName
            let balancePart: String
            if let oldBalance, let newBalance {
                balancePart = " | \(formatAmount(cents: oldBalance, currency: currency)) -> \(formatAmount(cents: newBalance, currency: currency))"
            } else {
                balancePart = ""
            }
            let amount: String
            if ticketTypeId == 3 || priceCents < 0 {
                amount = "+\(formatAmount(cents: abs(priceCents), currency: currency))"
            } else {
                amount = "-\(formatAmount(cents: abs(priceCents), currency: currency))"
            }

            result.append(
                CardHistoryItem(
                    id: ticketId,
                    sourceType: .ticket,
                    timestampMs: saleTimeMs,
                    title: title,
                    subtitle: detailLabel + balancePart,
                    amountText: amount
                )
            )
        }

        for (index, tx) in transactions.enumerated() {
            let createdAtSec = readInt64(tx, keys: ["createdAt"])
            let createdAtMs = normalizeHistoryTimestamp(createdAtSec * 1000)
            let txType = readInt(tx, keys: ["transactionType"])
            let changes = tx["changes"] as? [[String: Any]] ?? []
            let subtitle = buildChangesSubtitle(changes)

            result.append(
                CardHistoryItem(
                    id: "transaction-\(createdAtMs)-\(index)",
                    sourceType: .transaction,
                    timestampMs: createdAtMs,
                    title: "Transaction #\(txType)",
                    subtitle: subtitle,
                    amountText: ""
                )
            )
        }

        return result.sorted { $0.timestampMs > $1.timestampMs }
    }

    private func buildChangesSubtitle(_ changes: [[String: Any]]) -> String {
        var parts: [String] = []
        for change in changes {
            let value = readString(change, keys: ["value", "valueAfter", "valueBefore"])
            if !value.isEmpty {
                parts.append(value)
            }
        }
        return parts.isEmpty ? "Transaction details" : parts.joined(separator: " | ")
    }

    private func extractTemplateBase64(_ raw: String) -> String {
        if raw.isEmpty {
            return ""
        }
        if let data = raw.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let value = object["base64"] as? String {
            return value
        }
        return ""
    }

    private func firstCard(from dict: [String: Any]) -> [String: Any]? {
        if let card = dict["card"] as? [String: Any] {
            return card
        }
        if let cards = dict["cards"] as? [[String: Any]], let first = cards.first {
            return first
        }
        if let user = dict["wertyzUser"] as? [String: Any] {
            return firstCard(from: user)
        }
        if let user = dict["user"] as? [String: Any] {
            return firstCard(from: user)
        }
        return nil
    }

    private func firstTicket(from card: [String: Any]) -> [String: Any]? {
        guard let tickets = card["tickets"] as? [[String: Any]] else {
            return nil
        }
        if let active = tickets.first(where: { ($0["active"] as? Bool) == true }) {
            return active
        }
        return tickets.first
    }

    private func readString(_ dict: [String: Any], keys: [String]) -> String {
        for key in keys {
            if let value = dict[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    return trimmed
                }
            }
        }
        return ""
    }

    private func readInt64(_ dict: [String: Any], keys: [String]) -> Int64 {
        for key in keys {
            if let v = dict[key] as? Int64 { return v }
            if let v = dict[key] as? Int { return Int64(v) }
            if let v = dict[key] as? Double { return Int64(v) }
            if let v = dict[key] as? String, let parsed = Int64(v) { return parsed }
        }
        return 0
    }

    private func readOptionalInt64(_ dict: [String: Any], keys: [String]) -> Int64? {
        for key in keys {
            if let v = dict[key] as? Int64 { return v }
            if let v = dict[key] as? Int { return Int64(v) }
            if let v = dict[key] as? Double { return Int64(v) }
            if let v = dict[key] as? String, let parsed = Int64(v) { return parsed }
        }
        return nil
    }

    private func readInt(_ dict: [String: Any], keys: [String]) -> Int {
        Int(readInt64(dict, keys: keys))
    }

    private func readMoney(_ dict: [String: Any], keys: [String]) -> Double? {
        for key in keys {
            if let v = dict[key] as? Double {
                if v.rounded(.towardZero) == v {
                    return v / 100.0
                }
                return v
            }
            if let v = dict[key] as? Int64 {
                return Double(v) / 100.0
            }
            if let v = dict[key] as? Int {
                return Double(v) / 100.0
            }
            if let v = dict[key] as? String {
                let normalized = v.replacingOccurrences(of: ",", with: ".")
                if let parsed = Double(normalized) {
                    if normalized.contains(".") {
                        return parsed
                    }
                    return parsed / 100.0
                }
            }
        }
        return nil
    }

    private func formatAmount(cents: Int64, currency: String) -> String {
        let value = Double(cents) / 100.0
        let amount = String(format: "%.2f", value)
        return currency.isEmpty ? amount : "\(amount) \(currency)"
    }

    private func normalizeHistoryTimestamp(_ rawMs: Int64) -> Int64 {
        if rawMs <= 0 {
            return rawMs
        }
        let date = Date(timeIntervalSince1970: TimeInterval(rawMs) / 1000.0)
        let offsetSeconds = TimeZone.current.secondsFromGMT(for: date)
        return rawMs - Int64(offsetSeconds * 1000)
    }

    private func performRequest(
        session: URLSession,
        path: String,
        method: String,
        body: String? = nil,
        contentType: String? = nil,
        additionalHeaders: [String: String] = [:]
    ) async throws -> Data {
        let cleanPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let url = baseURL.appendingPathComponent(cleanPath)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", forHTTPHeaderField: "User-Agent")
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")

        if let body {
            request.httpBody = body.data(using: .utf8)
        }
        if let contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        additionalHeaders.forEach { key, value in
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NSError(domain: "testEM", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid HTTP response"])
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw NSError(
                domain: "testEM",
                code: httpResponse.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "HTTP \(httpResponse.statusCode)"]
            )
        }
        return data
    }

    private func urlEncode(_ input: String) -> String {
        input.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? input
    }

    private func base64ToHex(_ encoded: String) -> String? {
        let cleaned = encoded.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        guard let data = Data(base64Encoded: cleaned, options: [.ignoreUnknownCharacters]) else {
            return nil
        }
        return data.map { String(format: "%02x", $0) }.joined()
    }

    private func generateUid() -> String {
        var bytes = [UInt8](repeating: 0, count: 4)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

private struct QRCodeView: View {
    let payload: String
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    var body: some View {
        if let image = generateImage(payload) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.secondary.opacity(0.15))
                .overlay(
                    VStack(spacing: 4) {
                        Text("No Token")
                        Text("Waiting for token...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                )
        }
    }

    private func generateImage(_ string: String) -> UIImage? {
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let outputImage = filter.outputImage else { return nil }
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

private struct PinSetupView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var confirmPin = ""
    @State private var error = ""

    var body: some View {
        Form {
            Section("Set App PIN") {
                SecureField("PIN (4-8 digits)", text: $pin)
                    .keyboardType(.numberPad)
                SecureField("Confirm PIN", text: $confirmPin)
                    .keyboardType(.numberPad)
            }

            Section {
                Button("Save PIN") {
                    guard pin == confirmPin else {
                        error = "PINs do not match."
                        return
                    }
                    if viewModel.setPin(pin) {
                        pin = ""
                        confirmPin = ""
                        error = ""
                    } else {
                        error = "PIN must be 4-8 digits."
                    }
                }
            }

            if !error.isEmpty {
                Section("Error") {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
    }
}

private struct PinUnlockView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var error = ""

    var body: some View {
        Form {
            Section("Unlock") {
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)

                Button("Unlock with PIN") {
                    if viewModel.verifyPin(pin) {
                        error = ""
                        pin = ""
                    } else {
                        error = "Incorrect PIN"
                    }
                }

                if viewModel.biometricEnabled {
                    Button("Use Face ID / Touch ID") {
                        Task {
                            let ok = await viewModel.unlockWithBiometrics()
                            if !ok {
                                error = "Biometric unlock failed"
                            } else {
                                error = ""
                            }
                        }
                    }
                }
            }

            if !error.isEmpty {
                Section("Error") {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
    }
}

struct ContentView: View {
    @StateObject private var viewModel = TestEMViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var settingsPage: SettingsPage = .root
    @State private var showAccountDialog = false
    @State private var showHistoryScreen = false
    @State private var showTokenInfoDialog = false
    @State private var showFullscreenQr = false
    @State private var showNfcUidDialog = false
    @State private var nfcUidToShow = ""
    @State private var showLogoutConfirm = false
    @State private var showChangePinSheet = false
    @State private var changePinCurrent = ""
    @State private var changePinNew = ""
    @State private var changePinConfirm = ""
    @State private var changePinError = ""
    @State private var customTimeout = ""
    @State private var presetName = ""
    @State private var primaryHex = ""
    @State private var secondaryHex = ""
    @State private var tertiaryHex = ""

    var body: some View {
        NavigationStack {
            ZStack {
                appBackground
                    .ignoresSafeArea()

                Group {
                    if !viewModel.isPinSet {
                        PinSetupView(viewModel: viewModel)
                    } else if !viewModel.isAppUnlocked {
                        PinUnlockView(viewModel: viewModel)
                    } else if viewModel.isLoggedIn {
                        daemonView
                    } else {
                        loginView
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .navigationTitle("testEM")
            .toolbar {
                if viewModel.isLoggedIn && viewModel.isAppUnlocked {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            settingsPage = .root
                            showSettings = true
                        } label: {
                            Image(systemName: "gearshape.fill")
                        }
                    }
                }
            }
        }
        .onChange(of: scenePhase) { _, newValue in
            viewModel.handleScenePhase(newValue)
        }
        .tint(viewModel.currentAccentColor)
        .sheet(isPresented: $showSettings) {
            settingsSheet
        }
        .sheet(isPresented: $showChangePinSheet) {
            changePinSheet
        }
        .sheet(isPresented: $showAccountDialog) {
            accountDialogSheet
        }
        .sheet(isPresented: $showHistoryScreen) {
            historyScreenSheet
        }
        .sheet(isPresented: $showTokenInfoDialog) {
            tokenInfoSheet
        }
        .fullScreenCover(isPresented: $showFullscreenQr) {
            fullscreenQrView
        }
        .alert("NFC UID", isPresented: $showNfcUidDialog) {
            Button("Copy") {
                UIPasteboard.general.string = nfcUidToShow
            }
            Button("Close", role: .cancel) { }
        } message: {
            Text("Copy this UID to set it on the website:\n\n\(nfcUidToShow)")
        }
        .alert("Logout?", isPresented: $showLogoutConfirm) {
            Button("Cancel", role: .cancel) { }
            Button("Yes, Logout", role: .destructive) {
                viewModel.logout()
                showSettings = false
            }
        } message: {
            Text("Are you sure you want to logout?")
        }
    }

    private var appBackground: some View {
        let top = viewModel.amoledEnabled ? Color.black : Color(red: 0.92, green: 0.97, blue: 0.95)
        let bottom = viewModel.amoledEnabled ? Color(red: 0.06, green: 0.06, blue: 0.06) : Color(red: 0.84, green: 0.92, blue: 0.89)
        return LinearGradient(colors: [top, bottom], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private var loginView: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("testEM")
                        .font(.largeTitle.weight(.bold))
                    Text("Real-time QR Token Generator")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                cardContainer("Credentials") {
                    VStack(spacing: 12) {
                        TextField("Email or Username", text: $viewModel.email)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                        SecureField("Password", text: $viewModel.password)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
                    }
                }

                Button {
                    viewModel.login()
                } label: {
                    HStack {
                        if viewModel.isLoggingIn {
                            ProgressView()
                                .tint(.white)
                        }
                        Text(viewModel.isLoggingIn ? "Logging in..." : "Login")
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isLoggingIn)
                .controlSize(.large)

                cardContainer("Status") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(viewModel.statusMessage)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if !viewModel.errorMessage.isEmpty {
                            Text(viewModel.errorMessage)
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    private var daemonView: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack {
                    Button {
                        showAccountDialog = true
                    } label: {
                        Label(viewModel.email.isEmpty ? "testEM" : viewModel.email, systemImage: "person.circle")
                    }
                    .buttonStyle(.bordered)

                    Spacer()

                    Button {
                        settingsPage = .root
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape.fill")
                    }
                    .buttonStyle(.bordered)
                }

                ForEach(viewModel.layoutOrder, id: \.rawValue) { id in
                    if !viewModel.hiddenSections.contains(id) || !hideableSections.contains(id) {
                        sectionView(for: id)
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var hideableSections: Set<SectionId> {
        [.status, .nfc, .error]
    }

    @ViewBuilder
    private func sectionView(for id: SectionId) -> some View {
        switch id {
        case .status:
            cardContainer("Polling status") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Status:")
                            .fontWeight(.bold)
                        Spacer()
                        Text(viewModel.isPolling ? "Polling Active" : "Polling Paused")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(viewModel.isPolling ? Color.green.opacity(0.2) : Color.gray.opacity(0.2), in: Capsule())
                    }
                    Text("Last Update: \(viewModel.lastUpdated?.formatted(date: .omitted, time: .standard) ?? "Never")")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if !viewModel.statusMessage.isEmpty {
                        Text(viewModel.statusMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .qr:
            cardContainer("Current QR Code") {
                VStack(spacing: 12) {
                    HStack {
                        Spacer()
                        Button {
                            showTokenInfoDialog = true
                        } label: {
                            Image(systemName: "info.circle")
                        }
                        .buttonStyle(.bordered)
                    }

                    QRCodeView(payload: viewModel.qrPayload)
                        .frame(width: 260, height: 260)
                        .onTapGesture {
                            showFullscreenQr = true
                        }
                    if !viewModel.tokenHex.isEmpty {
                        Text(viewModel.tokenHex)
                            .font(.system(size: 12, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        case .nfc:
            cardContainer("NFC") {
                VStack(spacing: 10) {
                    Button(viewModel.nfcEnabled ? "Switch to QR" : "Switch to NFC") {
                        let uid = viewModel.toggleNfc()
                        if let uid {
                            nfcUidToShow = uid
                            showNfcUidDialog = true
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.bordered)
                    .disabled(viewModel.qrPayload.isEmpty)

                    if viewModel.nfcEnabled && !viewModel.nfcUid.isEmpty {
                        Text("UID: \(viewModel.nfcUid)")
                            .font(.caption)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .controls:
            cardContainer("Controls") {
                VStack(spacing: 10) {
                    Button(viewModel.isPolling ? "Stop" : "Get QR") {
                        if viewModel.isPolling {
                            viewModel.stopPolling()
                        } else {
                            viewModel.startPolling()
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .tint(viewModel.isPolling ? .red : viewModel.currentAccentColor)

                    Button("Ticket & Payment History") {
                        showHistoryScreen = true
                    }
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.qrPayload.isEmpty)

                    Button("Refresh") {
                        Task {
                            do {
                                try await viewModel.loadAccountDetails()
                                await viewModel.loadCardHistory()
                            } catch {
                                viewModel.errorMessage = "Refresh failed: \(error.localizedDescription)"
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        case .error:
            if !viewModel.errorMessage.isEmpty {
                cardContainer("Errors") {
                    Text(viewModel.errorMessage)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private func cardContainer<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.headline)
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background(viewModel.amoledEnabled ? Color(.secondarySystemBackground) : Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .shadow(color: Color.black.opacity(viewModel.amoledEnabled ? 0.0 : 0.08), radius: 10, x: 0, y: 3)
    }

    private var settingsSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    switch settingsPage {
                    case .root:
                        settingsRootView
                    case .layout:
                        layoutSettingsView
                    case .security:
                        securitySettingsView
                    }
                }
                .padding(16)
            }
            .navigationTitle(settingsPageTitle)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") {
                        if settingsPage == .root {
                            showSettings = false
                        } else {
                            settingsPage = .root
                        }
                    }
                }
            }
            .background(viewModel.amoledEnabled ? Color.black : Color(.systemGroupedBackground))
        }
    }

    private var settingsPageTitle: String {
        switch settingsPage {
        case .root: return "Settings"
        case .layout: return "Layout"
        case .security: return "Security"
        }
    }

    private var settingsRootView: some View {
        VStack(spacing: 14) {
            cardContainer("Theme Presets") {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(viewModel.themePresets) { preset in
                        HStack {
                            Image(systemName: viewModel.selectedThemeId == preset.id ? "largecircle.fill.circle" : "circle")
                            Text(preset.name)
                            Spacer()
                            HStack(spacing: 6) {
                                Circle().fill(preset.primary).frame(width: 12, height: 12)
                                Circle().fill(preset.secondary).frame(width: 12, height: 12)
                                Circle().fill(preset.tertiary).frame(width: 12, height: 12)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            viewModel.selectThemePreset(preset.id)
                        }
                    }

                    Toggle("AMOLED mode", isOn: Binding(
                        get: { viewModel.amoledEnabled },
                        set: { viewModel.setAmoledEnabled($0) }
                    ))
                }
            }

            cardContainer("Create Preset") {
                VStack(alignment: .leading, spacing: 10) {
                    TextField("Preset name", text: $presetName)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                    TextField("Primary hex (RRGGBB or AARRGGBB)", text: $primaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                    TextField("Secondary hex (RRGGBB or AARRGGBB)", text: $secondaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                    TextField("Tertiary hex (RRGGBB or AARRGGBB)", text: $tertiaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                    Button("Save preset") {
                        guard
                            let primary = parseHexColor(primaryHex),
                            let secondary = parseHexColor(secondaryHex),
                            let tertiary = parseHexColor(tertiaryHex)
                        else {
                            return
                        }
                        viewModel.addThemePreset(
                            name: presetName,
                            primary: primary,
                            secondary: secondary,
                            tertiary: tertiary
                        )
                        presetName = ""
                        primaryHex = ""
                        secondaryHex = ""
                        tertiaryHex = ""
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        presetName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                        parseHexColor(primaryHex) == nil ||
                        parseHexColor(secondaryHex) == nil ||
                        parseHexColor(tertiaryHex) == nil
                    )
                }
            }

            cardContainer("Language") {
                VStack(alignment: .leading, spacing: 8) {
                    languageRow(code: "sk", label: "Slovak (preferred)")
                    languageRow(code: "en", label: "English")
                }
            }

            cardContainer("Layout") {
                Button("Open Layout Settings") {
                    settingsPage = .layout
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }

            cardContainer("Security") {
                Button("Open Security Settings") {
                    settingsPage = .security
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }

            cardContainer("Account") {
                VStack(spacing: 10) {
                    Button("Logout", role: .destructive) {
                        showLogoutConfirm = true
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.borderedProminent)
                }
            }
        }
    }

    private var layoutSettingsView: some View {
        VStack(spacing: 12) {
            ForEach(Array(viewModel.layoutOrder.enumerated()), id: \.element.rawValue) { index, id in
                cardContainer(sectionTitle(id)) {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sectionTitle(id))
                                .fontWeight(.medium)
                            if hideableSections.contains(id) {
                                Text(viewModel.hiddenSections.contains(id) ? "Hidden" : "Visible")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                Text("Always visible")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if hideableSections.contains(id) {
                            Button(viewModel.hiddenSections.contains(id) ? "Show" : "Hide") {
                                viewModel.setSectionHidden(id, hidden: !viewModel.hiddenSections.contains(id))
                            }
                        }
                        Button("Up") {
                            viewModel.moveLayoutItem(id, direction: -1)
                        }
                        .disabled(index == 0)
                        Button("Down") {
                            viewModel.moveLayoutItem(id, direction: 1)
                        }
                        .disabled(index == viewModel.layoutOrder.count - 1)
                    }
                }
            }
        }
    }

    private var securitySettingsView: some View {
        VStack(spacing: 14) {
            cardContainer("Biometrics") {
                Toggle("Biometrics", isOn: Binding(
                    get: { viewModel.biometricEnabled },
                    set: { viewModel.setBiometricEnabled($0) }
                ))
            }

            cardContainer("Lock Timeout") {
                VStack(alignment: .leading, spacing: 6) {
                    lockTimeoutRow(seconds: 0, label: "Immediately")
                    lockTimeoutRow(seconds: 30, label: "After 30 seconds")
                    lockTimeoutRow(seconds: 60, label: "After 1 minute")
                    lockTimeoutRow(seconds: 300, label: "After 5 minutes")

                    TextField("Custom seconds", text: $customTimeout)
                        .keyboardType(.numberPad)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
                        .onChange(of: customTimeout) { _, value in
                            let digits = value.filter { $0.isNumber }
                            if digits != value {
                                customTimeout = digits
                            }
                            if let seconds = Int(digits) {
                                viewModel.setLockTimeoutSeconds(seconds)
                            }
                        }
                }
            }

            cardContainer("PIN") {
                Button("Change PIN") {
                    changePinCurrent = ""
                    changePinNew = ""
                    changePinConfirm = ""
                    changePinError = ""
                    showChangePinSheet = true
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var changePinSheet: some View {
        NavigationStack {
            Form {
                Section("Current") {
                    SecureField("Current PIN", text: $changePinCurrent)
                        .keyboardType(.numberPad)
                }
                Section("New") {
                    SecureField("New PIN (4-8 digits)", text: $changePinNew)
                        .keyboardType(.numberPad)
                    SecureField("Confirm New PIN", text: $changePinConfirm)
                        .keyboardType(.numberPad)
                }
                if !changePinError.isEmpty {
                    Section("Error") {
                        Text(changePinError).foregroundStyle(.red)
                    }
                }
                Section {
                    Button("Update") {
                        if changePinCurrent.count < 4 {
                            changePinError = "Enter current PIN."
                            return
                        }
                        if changePinNew.count < 4 {
                            changePinError = "New PIN is too short."
                            return
                        }
                        if changePinNew != changePinConfirm {
                            changePinError = "PIN confirmation does not match."
                            return
                        }
                        if viewModel.changePin(currentPin: changePinCurrent, newPin: changePinNew) {
                            showChangePinSheet = false
                        } else {
                            changePinError = "Current PIN is incorrect."
                        }
                    }
                }
            }
            .navigationTitle("Change PIN")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showChangePinSheet = false
                    }
                }
            }
        }
    }

    private func sectionTitle(_ id: SectionId) -> String {
        switch id {
        case .status: return "Polling status"
        case .qr: return "QR code"
        case .nfc: return "NFC button"
        case .controls: return "Controls"
        case .error: return "Errors"
        }
    }

    private var accountDialogSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    cardContainer("Account") {
                        VStack(alignment: .leading, spacing: 8) {
                            labeled("Name", viewModel.email.isEmpty ? "testEM" : viewModel.email)
                            labeled("Email", viewModel.email)
                            labeled("SNR", viewModel.serialNumber)
                            if !viewModel.nfcUid.isEmpty {
                                labeled("NFC UID", viewModel.nfcUid)
                            }
                            if !viewModel.accountDetails.cardTypeName.isEmpty {
                                labeled("Card Type", viewModel.accountDetails.cardTypeName)
                            }
                            if !viewModel.accountDetails.organizationName.isEmpty {
                                labeled("Organization", viewModel.accountDetails.organizationName)
                            }
                            if viewModel.accountDetails.cardValidFrom > 0 || viewModel.accountDetails.cardValidTo > 0 {
                                labeled("Card Valid", "\(dateText(fromMs: viewModel.accountDetails.cardValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.cardValidTo))")
                            }
                            if viewModel.accountDetails.ticketValidFrom > 0 || viewModel.accountDetails.ticketValidTo > 0 {
                                labeled("Ticket Valid", "\(dateText(fromMs: viewModel.accountDetails.ticketValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.ticketValidTo))")
                            }
                            if viewModel.accountDetails.discountValidFrom > 0 || viewModel.accountDetails.discountValidTo > 0 {
                                labeled("Discount Valid", "\(dateText(fromMs: viewModel.accountDetails.discountValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.discountValidTo))")
                            }
                            if let credit = viewModel.accountDetails.creditLastBalance {
                                labeled("Credit", String(format: "%.2f %@", credit, viewModel.accountDetails.currencySymbol))
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Account")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showAccountDialog = false
                    }
                }
            }
        }
    }

    private var historyScreenSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    cardContainer("Tickets & Payments History") {
                        VStack(alignment: .leading, spacing: 10) {
                            if viewModel.historyState.isLoading {
                                ProgressView()
                            }

                            if !viewModel.historyState.errorMessage.isEmpty {
                                Text(viewModel.historyState.errorMessage)
                                    .foregroundStyle(.red)
                            }

                            if !viewModel.historyState.isLoading && viewModel.historyState.items.isEmpty {
                                Text("No history found.")
                                    .foregroundStyle(.secondary)
                            }

                            ForEach(viewModel.historyState.items) { item in
                                let isPositive = item.amountText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("+")
                                let isNegative = item.amountText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("-")
                                let cardColor: Color = {
                                    if item.sourceType == .transaction {
                                        return viewModel.currentAccentColor.opacity(0.18)
                                    }
                                    if isPositive {
                                        return Color.green.opacity(0.18)
                                    }
                                    if isNegative {
                                        return Color.red.opacity(0.14)
                                    }
                                    return Color.secondary.opacity(0.12)
                                }()
                                let amountColor: Color = {
                                    if isPositive {
                                        return Color(red: 0.11, green: 0.50, blue: 0.23)
                                    }
                                    if isNegative {
                                        return .red
                                    }
                                    return .primary
                                }()

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(dateText(fromMs: item.timestampMs))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)

                                    HStack {
                                        Text(item.title)
                                            .font(.subheadline.weight(.semibold))
                                        Spacer()
                                        if !item.amountText.isEmpty {
                                            Text(item.amountText)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(amountColor)
                                        }
                                    }

                                    if !item.subtitle.isEmpty {
                                        Text(item.subtitle)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(cardColor, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Tickets & Payments History")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") {
                        showHistoryScreen = false
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Refresh") {
                        Task { await viewModel.loadCardHistory() }
                    }
                    .disabled(viewModel.historyState.isLoading)
                }
            }
        }
    }

    private var tokenInfoSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    if viewModel.tokenHex.isEmpty {
                        cardContainer("Token Information") {
                            Text("Waiting for token...")
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else {
                        cardContainer("Token Information") {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("HEX: \(viewModel.tokenHex)")
                                    .font(.system(size: 12, design: .monospaced))
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Text("B64: \(viewModel.tokenBase64)")
                                    .font(.system(size: 12, design: .monospaced))
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Token Information")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showTokenInfoDialog = false
                    }
                }
            }
        }
    }

    private var fullscreenQrView: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            VStack {
                Spacer()
                QRCodeView(payload: viewModel.qrPayload)
                    .frame(width: 300, height: 300)
                Spacer()
                Button("Close") {
                    showFullscreenQr = false
                }
                .buttonStyle(.borderedProminent)
                .padding(.bottom, 30)
            }
            .padding()
        }
    }

    private func languageRow(code: String, label: String) -> some View {
        HStack {
            Image(systemName: viewModel.languageCode == code ? "largecircle.fill.circle" : "circle")
            Text(label)
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            viewModel.setLanguageCode(code)
        }
    }

    private func lockTimeoutRow(seconds: Int, label: String) -> some View {
        HStack {
            Image(systemName: viewModel.lockTimeoutSeconds == seconds ? "largecircle.fill.circle" : "circle")
            Text(label)
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            viewModel.setLockTimeoutSeconds(seconds)
        }
    }

    private func labeled(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title + ":")
                .foregroundStyle(.secondary)
            Spacer()
            Text(value.isEmpty ? "-" : value)
        }
    }

    private func parseHexColor(_ raw: String) -> Color? {
        let cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
        let normalized: String
        if cleaned.count == 6 {
            normalized = "FF" + cleaned
        } else if cleaned.count == 8 {
            normalized = cleaned
        } else {
            return nil
        }

        guard let value = UInt64(normalized, radix: 16) else { return nil }
        let a = Double((value & 0xFF000000) >> 24) / 255.0
        let r = Double((value & 0x00FF0000) >> 16) / 255.0
        let g = Double((value & 0x0000FF00) >> 8) / 255.0
        let b = Double(value & 0x000000FF) / 255.0
        return Color(red: r, green: g, blue: b, opacity: a)
    }

    private func dateText(fromMs value: Int64) -> String {
        if value <= 0 {
            return "-"
        }
        let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000.0)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}



