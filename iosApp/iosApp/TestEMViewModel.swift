import SwiftUI
import Foundation
import LocalAuthentication
import CryptoKit
import Security
import UIKit

@MainActor
final class TestEMViewModel: ObservableObject {
    @Published var email: String {
        didSet {
            credentialsManager.setEmail(email)
        }
    }
    @Published var password: String {
        didSet {
            credentialsManager.setPassword(password)
        }
    }
    @Published var serialNumber: String {
        didSet {
            credentialsManager.setSerial(serialNumber)
        }
    }

    @Published var isLoggingIn = false
    @Published var isLoggedIn = false
    @Published var isSessionReady = false
    @Published var isPolling = false
    @Published var nfcEnabled = false
    @Published var nfcUid = "" {
        didSet {
            credentialsManager.setNfcUid(nfcUid)
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

    private let credentialsManager = CredentialsManager.shared
    private var daemonService: QRDaemonService?
    private var backgroundAt: Date?
    private var isBiometricPromptInProgress = false
    private var didAttemptAutoBiometricThisForeground = false
    private var hasEstablishedSession = false

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
        self.email = credentialsManager.loadEmail()
        self.password = credentialsManager.loadPassword()
        self.serialNumber = credentialsManager.loadSerial()
        self.nfcUid = credentialsManager.loadNfcUid()
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
        hasEstablishedSession = false
        isLoggedIn = true
        isSessionReady = true
        errorMessage = ""
        statusMessage = "Connecting..."
        initializeDaemonService()
        persistCredentials()
        isLoggingIn = false
        startPolling()
    }

    func logout() {
        stopPolling()
        daemonService?.clearSessionCookies()
        daemonService = nil
        isLoggingIn = false
        isLoggedIn = false
        isSessionReady = false
        hasEstablishedSession = false
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
        guard isLoggedIn, daemonService != nil else { return }
        if isPolling { return }
        isPolling = true
        statusMessage = "Starting polling..."
        daemonService?.startPolling()
    }

    func stopPolling() {
        daemonService?.stopPolling()
        isPolling = false
        errorMessage = ""
        statusMessage = "Polling stopped"
    }

    func loadCardHistory(limit: Int = 20) async {
        guard isLoggedIn && isSessionReady else { return }
        historyState.isLoading = true
        historyState.errorMessage = ""
        do {
            let items = try await daemonService?.fetchCardHistory(limit: limit) ?? []
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
        daemonService?.setNfcMode(enabled: nfcEnabled, uid: nfcUid)
        if nfcEnabled {
            if nfcUid.isEmpty {
                nfcUid = generateUid()
                daemonService?.setNfcMode(enabled: true, uid: nfcUid)
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

    var appTitleName: String {
        let trimmed = accountDetails.accountName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "testEM" : trimmed
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
            if let bg = backgroundAt {
                if timeout == 0 {
                    isAppUnlocked = false
                } else if Date().timeIntervalSince(bg) >= Double(timeout) {
                    isAppUnlocked = false
                }
            }
            backgroundAt = nil
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
        credentialsManager.setEmail(email)
        credentialsManager.setPassword(password)
        credentialsManager.setSerial(serialNumber)
    }

    private func initializeDaemonService() {
        daemonService?.stopPolling()
        daemonService = QRDaemonService(
            baseURL: QRDaemonConfig.baseURL,
            username: email.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password,
            initialSerialNumber: serialNumber,
            initialNfcUid: nfcUid,
            initialNfcEnabled: nfcEnabled,
            onTokenUpdate: { [weak self] hex, base64 in
                Task { @MainActor in
                    guard let self else { return }
                    self.hasEstablishedSession = true
                    self.tokenHex = hex
                    self.tokenBase64 = base64
                    self.qrPayload = base64
                    self.lastUpdated = Date()
                    self.errorMessage = ""
                    self.isLoggingIn = false
                    self.isPolling = true
                }
            },
            onError: { [weak self] error in
                Task { @MainActor in
                    guard let self else { return }
                    self.errorMessage = error
                    self.isLoggingIn = false
                    self.statusMessage = "Error: \(error)"
                }
            },
            onUserName: { [weak self] name in
                Task { @MainActor in
                    guard let self else { return }
                    self.accountDetails.accountName = name
                }
            },
            onSerialNumber: { [weak self] snr in
                Task { @MainActor in
                    guard let self else { return }
                    self.serialNumber = snr
                    self.credentialsManager.setSerial(snr)
                }
            },
            onAccountInfo: { [weak self] details in
                Task { @MainActor in
                    guard let self else { return }
                    self.hasEstablishedSession = true
                    self.accountDetails = details
                    self.isLoggingIn = false
                    self.isSessionReady = true
                }
            },
            onStatus: { [weak self] status in
                Task { @MainActor in
                    guard let self else { return }
                    self.statusMessage = status
                    if status == "Session ready" {
                        self.hasEstablishedSession = true
                        self.isLoggingIn = false
                        self.isSessionReady = true
                    }
                }
            }
        )
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

    func loadAccountDetails() async throws {
        guard let daemonService else {
            throw NSError(domain: "testEM", code: -1, userInfo: [NSLocalizedDescriptionKey: "Service not initialized"])
        }
        try await daemonService.refreshAccountDetails()
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
