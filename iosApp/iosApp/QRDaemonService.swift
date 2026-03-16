import Foundation

final class QRDaemonService {
    private let baseURL: URL
    private let cookieStorage = HTTPCookieStorage()

    init(baseURL: URL = QRDaemonConfig.baseURL) {
        self.baseURL = baseURL
    }

    func warmSessionAndLogin(email: String, password: String) async throws {
        let session = makeSession()
        _ = try await performRequest(session: session, path: "/", method: "GET")
        _ = try await performRequest(session: session, path: "/account", method: "GET")

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
    }

    func getQrToken(serialNumber: String) async throws -> String {
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
        return encoded
    }

    func getAccountDetailRaw() async throws -> Data {
        let session = makeSession()
        return try await performRequest(
            session: session,
            path: "/userapi/getAccountDetail",
            method: "GET",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )
    }

    func getCardHistoryRaw(serialNumber: String, limit: Int) async throws -> Data {
        let session = makeSession()
        let path = "/cardapi/getCardHistory/\(serialNumber)/0/\(limit)"
        return try await performRequest(
            session: session,
            path: path,
            method: "GET",
            additionalHeaders: ["X-Requested-With": "XMLHttpRequest"]
        )
    }

    func clearSessionCookies() {
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
}
