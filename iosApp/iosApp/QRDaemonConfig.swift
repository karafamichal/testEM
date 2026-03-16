import Foundation

enum QRDaemonConfig {
    static let baseURL = URL(string: "https://m.mhdbb.qrbus.me")!
    static let accountURL = baseURL.appendingPathComponent("account")
    static let loginURL = baseURL.appendingPathComponent("account/login")
    static let tokenAPI = baseURL.appendingPathComponent("cardapi/getQrToken")

    static let pollIntervalNs: UInt64 = 25_000_000_000
    static let retryDelayNs: UInt64 = 5_000_000_000
    static let shortRetryNs: UInt64 = 1_500_000_000
    static let tokenLength = 57
    static let qrCodeSize: CGFloat = 250
}
