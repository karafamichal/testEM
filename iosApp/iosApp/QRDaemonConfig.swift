import Foundation

enum QRDaemonConfig {
    static let baseURL = URL(string: "https://sadzv.qrbus.me")!
    static let pollIntervalNs: UInt64 = 25_000_000_000
}
