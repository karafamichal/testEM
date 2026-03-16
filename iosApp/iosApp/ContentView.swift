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
    let accent: Color
}

enum SettingsPage {
    case root
    case layout
    case security
}

enum SectionId: String, CaseIterable {
    case status
    case qr
    case account
    case history
    case controls
    case error
}

@MainActor
final class TestEMViewModel: ObservableObject {
    @Published var email: String
    @Published var password: String
    @Published var serialNumber: String

    @Published var isLoggingIn = false
    @Published var isLoggedIn = false
    @Published var isPolling = false
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
        ThemePreset(id: "classic", name: "Classic", accent: Color(red: 0.40, green: 0.31, blue: 0.64)),
        ThemePreset(id: "ocean", name: "Ocean", accent: Color(red: 0.07, green: 0.44, blue: 0.39)),
        ThemePreset(id: "sunset", name: "Sunset", accent: Color(red: 0.91, green: 0.37, blue: 0.02))
    ]
    @Published var selectedThemeId = "classic"
    @Published var layoutOrder: [SectionId] = [.status, .qr, .account, .history, .controls, .error]
    @Published var hiddenSections: Set<SectionId> = [.error]

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
    private let prefBiometricEnabled = "testem.biometricEnabled"
    private let prefLockTimeout = "testem.lockTimeoutSeconds"
    private let prefAmoledEnabled = "testem.amoledEnabled"
    private let prefLanguageCode = "testem.languageCode"
    private let prefSelectedThemeId = "testem.selectedThemeId"
    private let prefLayoutOrder = "testem.layoutOrder"
    private let prefHiddenSections = "testem.hiddenSections"
    private let prefPinSalt = "testem.pinSalt"
    private let prefPinHash = "testem.pinHash"

    init() {
        self.email = UserDefaults.standard.string(forKey: prefEmail) ?? ""
        self.password = UserDefaults.standard.string(forKey: prefPassword) ?? ""
        self.serialNumber = UserDefaults.standard.string(forKey: prefSerial) ?? ""
        self.biometricEnabled = UserDefaults.standard.object(forKey: prefBiometricEnabled) as? Bool ?? true
        self.amoledEnabled = UserDefaults.standard.object(forKey: prefAmoledEnabled) as? Bool ?? false
        self.languageCode = UserDefaults.standard.string(forKey: prefLanguageCode) ?? "sk"
        self.selectedThemeId = UserDefaults.standard.string(forKey: prefSelectedThemeId) ?? "classic"
        let savedTimeout = UserDefaults.standard.object(forKey: prefLockTimeout) as? Int ?? 30
        self.lockTimeoutSeconds = max(30, savedTimeout)
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

                try await loadAccountDetails()
                guard !serialNumber.isEmpty else {
                    throw NSError(
                        domain: "testEM",
                        code: 400,
                        userInfo: [NSLocalizedDescriptionKey: "SNR was not found in account details"]
                    )
                }

                persistCredentials()
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
        lockTimeoutSeconds = max(30, seconds)
        UserDefaults.standard.set(lockTimeoutSeconds, forKey: prefLockTimeout)
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

    var currentAccentColor: Color {
        themePresets.first(where: { $0.id == selectedThemeId })?.accent ?? .green
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
            let timeout = max(30, lockTimeoutSeconds)
            if let bg = backgroundAt {
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
        UserDefaults.standard.set(email, forKey: prefEmail)
        UserDefaults.standard.set(password, forKey: prefPassword)
        UserDefaults.standard.set(serialNumber, forKey: prefSerial)
    }

    private func clearSessionCookies() {
        cookieStorage.cookies?.forEach { cookieStorage.deleteCookie($0) }
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
                .overlay(Text("No QR yet"))
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
    @State private var showChangePinSheet = false
    @State private var changePinCurrent = ""
    @State private var changePinNew = ""
    @State private var changePinConfirm = ""
    @State private var changePinError = ""

    var body: some View {
        NavigationStack {
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
            .background(viewModel.amoledEnabled ? Color.black : Color(.systemGroupedBackground))
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
    }

    private var loginView: some View {
        ScrollView {
            VStack(spacing: 16) {
                GroupBox("Credentials") {
                    VStack(spacing: 12) {
                        TextField("Email", text: $viewModel.email)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)

                        SecureField("Password", text: $viewModel.password)
                            .textFieldStyle(.roundedBorder)
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
                        Text(viewModel.isLoggingIn ? "Signing in..." : "Login")
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isLoggingIn)

                GroupBox("Status") {
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
        [.status, .error]
    }

    @ViewBuilder
    private func sectionView(for id: SectionId) -> some View {
        switch id {
        case .status:
            cardContainer("Status") {
                VStack(alignment: .leading, spacing: 8) {
                    Text(viewModel.statusMessage)
                    if let updated = viewModel.lastUpdated {
                        Text("Last update: \(updated.formatted(date: .omitted, time: .standard))")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .qr:
            cardContainer("QR") {
                VStack(spacing: 12) {
                    QRCodeView(payload: viewModel.qrPayload)
                        .frame(width: 260, height: 260)
                    if !viewModel.tokenHex.isEmpty {
                        Text(viewModel.tokenHex)
                            .font(.system(size: 12, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        case .account:
            cardContainer("Account") {
                VStack(alignment: .leading, spacing: 6) {
                    labeled("SNR", viewModel.serialNumber)
                    labeled("Card Type", viewModel.accountDetails.cardTypeName)
                    labeled("Organization", viewModel.accountDetails.organizationName)
                    labeled("Card Valid To", dateText(fromMs: viewModel.accountDetails.cardValidTo))
                    labeled("Ticket Valid To", dateText(fromMs: viewModel.accountDetails.ticketValidTo))
                    if let credit = viewModel.accountDetails.creditLastBalance {
                        let currency = viewModel.accountDetails.currencySymbol
                        labeled("Credit", String(format: "%.2f %@", credit, currency))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .history:
            cardContainer("History") {
                VStack(alignment: .leading, spacing: 10) {
                    Button("Refresh History") {
                        Task { await viewModel.loadCardHistory() }
                    }

                    if viewModel.historyState.isLoading {
                        ProgressView()
                    }

                    if !viewModel.historyState.errorMessage.isEmpty {
                        Text(viewModel.historyState.errorMessage)
                            .foregroundStyle(.red)
                    }

                    ForEach(viewModel.historyState.items.prefix(20)) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(item.title).font(.headline)
                                Spacer()
                                Text(item.amountText)
                                    .foregroundStyle(item.amountText.hasPrefix("+") ? .green : .primary)
                            }
                            Text(item.subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text(dateText(fromMs: item.timestampMs))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Divider()
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .controls:
            cardContainer("Controls") {
                VStack(spacing: 10) {
                    Button(viewModel.isPolling ? "Stop Polling" : "Start Polling") {
                        if viewModel.isPolling {
                            viewModel.stopPolling()
                        } else {
                            viewModel.startPolling()
                        }
                    }
                    .frame(maxWidth: .infinity)

                    Button("Refresh Account") {
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
                    if settingsPage != .root {
                        Button("Back") {
                            settingsPage = .root
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        showSettings = false
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
                            Circle().fill(preset.accent).frame(width: 16, height: 16)
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
                Button("Logout", role: .destructive) {
                    viewModel.logout()
                    showSettings = false
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var layoutSettingsView: some View {
        VStack(spacing: 12) {
            ForEach(Array(viewModel.layoutOrder.enumerated()), id: \.element.rawValue) { index, id in
                cardContainer(sectionTitle(id)) {
                    HStack(spacing: 8) {
                        if hideableSections.contains(id) {
                            Toggle("Visible", isOn: Binding(
                                get: { !viewModel.hiddenSections.contains(id) },
                                set: { viewModel.setSectionHidden(id, hidden: !$0) }
                            ))
                        }
                        Spacer()
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
                Toggle("Enable Face ID / Touch ID", isOn: Binding(
                    get: { viewModel.biometricEnabled },
                    set: { viewModel.setBiometricEnabled($0) }
                ))
            }

            cardContainer("Lock Timeout") {
                VStack(alignment: .leading, spacing: 6) {
                    lockTimeoutRow(seconds: 30, label: "30 seconds")
                    lockTimeoutRow(seconds: 60, label: "1 minute")
                    lockTimeoutRow(seconds: 300, label: "5 minutes")
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
                    Button("Update PIN") {
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
        case .status: return "Polling Status"
        case .qr: return "QR Code"
        case .account: return "Account"
        case .history: return "History"
        case .controls: return "Controls"
        case .error: return "Errors"
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

    private func dateText(fromMs value: Int64) -> String {
        if value <= 0 {
            return "-"
        }
        let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000.0)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}



