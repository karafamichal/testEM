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
    @Published var lockTimeoutSeconds = 0

    private let baseURL = URL(string: "https://sadzv.qrbus.me")!
    private let cookieStorage = HTTPCookieStorage()
    private var pollTask: Task<Void, Never>?
    private let pollIntervalNs: UInt64 = 25_000_000_000
    private var backgroundAt: Date?

    private let prefEmail = "testem.email"
    private let prefPassword = "testem.password"
    private let prefSerial = "testem.serial"
    private let prefBiometricEnabled = "testem.biometricEnabled"
    private let prefLockTimeout = "testem.lockTimeoutSeconds"
    private let prefPinSalt = "testem.pinSalt"
    private let prefPinHash = "testem.pinHash"

    init() {
        self.email = UserDefaults.standard.string(forKey: prefEmail) ?? ""
        self.password = UserDefaults.standard.string(forKey: prefPassword) ?? ""
        self.serialNumber = UserDefaults.standard.string(forKey: prefSerial) ?? ""
        self.biometricEnabled = UserDefaults.standard.object(forKey: prefBiometricEnabled) as? Bool ?? true
        self.lockTimeoutSeconds = UserDefaults.standard.object(forKey: prefLockTimeout) as? Int ?? 0
        self.isPinSet = pinHashAndSalt() != nil
        self.isAppUnlocked = !isPinSet
    }

    func login() {
        guard isAppUnlocked else {
            errorMessage = "Unlock app first."
            return
        }
        guard !email.isEmpty, !password.isEmpty, !serialNumber.isEmpty else {
            errorMessage = "Fill in email, password, and serial number."
            return
        }

        isLoggingIn = true
        errorMessage = ""
        statusMessage = "Opening base URL..."

        Task {
            defer { isLoggingIn = false }
            do {
                try await warmSessionAndLogin()
                persistCredentials()
                isLoggedIn = true
                errorMessage = ""
                statusMessage = "Login successful"
                try? await loadAccountDetails()
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
        lockTimeoutSeconds = seconds
        UserDefaults.standard.set(seconds, forKey: prefLockTimeout)
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            if isPinSet {
                backgroundAt = Date()
            }
        case .active:
            guard isPinSet else { return }
            let timeout = lockTimeoutSeconds
            if timeout == 0 {
                isAppUnlocked = false
            } else if let bg = backgroundAt {
                if Date().timeIntervalSince(bg) >= Double(timeout) {
                    isAppUnlocked = false
                }
            }
            if biometricEnabled && !isAppUnlocked {
                Task {
                    _ = await unlockWithBiometrics()
                }
            }
        default:
            break
        }
    }

    func unlockWithBiometrics() async -> Bool {
        guard biometricEnabled else { return false }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            if let error {
                self.errorMessage = "Biometric unavailable: \(error.localizedDescription)"
            }
            return false
        }

        do {
            let ok = try await context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Unlock testEM"
            )
            if ok {
                isAppUnlocked = true
                return true
            }
        } catch {
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
            .padding()
            .navigationTitle("testEM")
        }
        .onChange(of: scenePhase) { _, newValue in
            viewModel.handleScenePhase(newValue)
        }
    }

    private var loginView: some View {
        Form {
            Section("Credentials") {
                TextField("Email", text: $viewModel.email)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                SecureField("Password", text: $viewModel.password)

                TextField("Serial Number", text: $viewModel.serialNumber)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            Section {
                Button {
                    viewModel.login()
                } label: {
                    if viewModel.isLoggingIn {
                        ProgressView()
                    } else {
                        Text("Login")
                    }
                }
                .disabled(viewModel.isLoggingIn)
            }

            if !viewModel.statusMessage.isEmpty {
                Section("Status") {
                    Text(viewModel.statusMessage)
                }
            }

            if !viewModel.errorMessage.isEmpty {
                Section("Error") {
                    Text(viewModel.errorMessage)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private var daemonView: some View {
        ScrollView {
            VStack(spacing: 16) {
                QRCodeView(payload: viewModel.qrPayload)
                    .frame(width: 260, height: 260)

                if !viewModel.tokenHex.isEmpty {
                    GroupBox("Token (Hex)") {
                        Text(viewModel.tokenHex)
                            .font(.system(size: 12, weight: .regular, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                if !viewModel.tokenBase64.isEmpty {
                    GroupBox("Token (Base64)") {
                        Text(viewModel.tokenBase64)
                            .font(.system(size: 12, weight: .regular, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                GroupBox("Account Details") {
                    VStack(alignment: .leading, spacing: 6) {
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

                GroupBox("History") {
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

                GroupBox("Security") {
                    VStack(alignment: .leading, spacing: 10) {
                        Toggle("Enable Face ID / Touch ID", isOn: Binding(
                            get: { viewModel.biometricEnabled },
                            set: { viewModel.setBiometricEnabled($0) }
                        ))

                        Picker("Lock Timeout", selection: Binding(
                            get: { viewModel.lockTimeoutSeconds },
                            set: { viewModel.setLockTimeoutSeconds($0) }
                        )) {
                            Text("Immediate").tag(0)
                            Text("30s").tag(30)
                            Text("1m").tag(60)
                            Text("5m").tag(300)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Status") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(viewModel.statusMessage)
                        if let updated = viewModel.lastUpdated {
                            Text("Last update: \(updated.formatted(date: .omitted, time: .standard))")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        if !viewModel.errorMessage.isEmpty {
                            Text(viewModel.errorMessage)
                                .foregroundStyle(.red)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                HStack(spacing: 12) {
                    Button(viewModel.isPolling ? "Stop Polling" : "Start Polling") {
                        if viewModel.isPolling {
                            viewModel.stopPolling()
                        } else {
                            viewModel.startPolling()
                        }
                    }

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

                    Button("Logout", role: .destructive) {
                        viewModel.logout()
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity)
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



