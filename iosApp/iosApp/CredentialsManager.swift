import Foundation

final class CredentialsManager {
    static let shared = CredentialsManager()

    private let defaults = UserDefaults.standard

    private enum Key {
        static let email = "testem.email"
        static let password = "testem.password"
        static let serial = "testem.serial"
        static let nfcUid = "testem.nfcUid"
        static let legacySerial = "serial_number"
        static let legacySerialAlt = "serial"
        static let legacyNfcUid = "nfc_uid"
        static let legacyNfcUidAlt = "nfcUid"
    }

    private init() {}

    func loadEmail() -> String { defaults.string(forKey: Key.email) ?? "" }
    func loadPassword() -> String { defaults.string(forKey: Key.password) ?? "" }
    func loadSerial() -> String {
        defaults.string(forKey: Key.serial)
            ?? defaults.string(forKey: Key.legacySerial)
            ?? defaults.string(forKey: Key.legacySerialAlt)
            ?? ""
    }

    func loadNfcUid() -> String {
        defaults.string(forKey: Key.nfcUid)
            ?? defaults.string(forKey: Key.legacyNfcUid)
            ?? defaults.string(forKey: Key.legacyNfcUidAlt)
            ?? ""
    }

    func setEmail(_ value: String) { defaults.set(value, forKey: Key.email) }
    func setPassword(_ value: String) { defaults.set(value, forKey: Key.password) }
    func setSerial(_ value: String) {
        defaults.set(value, forKey: Key.serial)
        defaults.set(value, forKey: Key.legacySerial)
    }

    func setNfcUid(_ value: String) {
        defaults.set(value, forKey: Key.nfcUid)
        defaults.set(value, forKey: Key.legacyNfcUid)
    }
}
