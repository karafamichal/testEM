import Foundation

final class QRDaemonService {
    private let baseURL: URL
    private let cookieStorage = HTTPCookieStorage()
    private let userAgent = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"

    init(baseURL: URL = QRDaemonConfig.baseURL) {
        self.baseURL = baseURL
    }

    func warmSessionAndLogin(email: String, password: String) async throws {
        let session = makeSession()
        _ = try await performRequest(session: session, path: "/", method: "GET")

        _ = try await performRequest(
            session: session,
            path: "/account",
            method: "GET",
            additionalHeaders: [
                "Referer": baseURL.absoluteString
            ]
        )

        ensureConsentCookies()

        let loginBody = "post[login]=\(urlEncode(email))&post[password]=\(urlEncode(password))"
        let loginURL = baseURL.appendingPathComponent("accountapi/login")
        var loginRequest = URLRequest(url: loginURL)
        loginRequest.httpMethod = "POST"
        loginRequest.httpBody = loginBody.data(using: .utf8)
        loginRequest.setValue("*/*", forHTTPHeaderField: "Accept")
        loginRequest.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        loginRequest.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        loginRequest.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        loginRequest.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        loginRequest.setValue(baseURL.absoluteString, forHTTPHeaderField: "Origin")
        loginRequest.setValue("no-cache", forHTTPHeaderField: "Pragma")
        loginRequest.setValue(baseURL.appendingPathComponent("account/login").absoluteString, forHTTPHeaderField: "Referer")
        loginRequest.setValue(userAgent, forHTTPHeaderField: "User-Agent")

        if let csrf = csrfToken(for: loginURL) {
            loginRequest.setValue(csrf, forHTTPHeaderField: "X-XSRF-TOKEN")
            loginRequest.setValue(csrf, forHTTPHeaderField: "X-CSRF-TOKEN")
        }

        let (loginData, loginResponseRaw) = try await session.data(for: loginRequest)
        guard let loginResponse = loginResponseRaw as? HTTPURLResponse else {
            throw NSError(domain: "testEM", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid login response"])
        }
        guard (200...299).contains(loginResponse.statusCode) else {
            throw NSError(domain: "testEM", code: loginResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Login HTTP \(loginResponse.statusCode)"])
        }

        maybePersistWpisCookie(from: loginResponse)

        let decoded = try JSONDecoder().decode(LoginResponse.self, from: loginData)
        if !decoded.success {
            throw NSError(domain: "testEM", code: 401, userInfo: [NSLocalizedDescriptionKey: "Server returned success=false"])
        }

        _ = try? await performRequest(
            session: session,
            path: "/accountapi/getUId",
            method: "GET",
            additionalHeaders: [
                "X-Requested-With": "XMLHttpRequest",
                "Referer": baseURL.absoluteString
            ]
        )
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
            additionalHeaders: tokenHeaders(path: "/account")
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
            additionalHeaders: tokenHeaders(path: "/account/login")
        )
    }

    func getCardHistoryRaw(serialNumber: String, limit: Int) async throws -> Data {
        let session = makeSession()
        let path = "/cardapi/getCardHistory/\(serialNumber)/0/\(limit)"
        return try await performRequest(
            session: session,
            path: path,
            method: "GET",
            additionalHeaders: tokenHeaders(path: "/account")
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
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
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

    private func tokenHeaders(path: String) -> [String: String] {
        var headers: [String: String] = [
            "X-Requested-With": "XMLHttpRequest",
            "Origin": baseURL.absoluteString,
            "Referer": baseURL.appendingPathComponent(path.hasPrefix("/") ? String(path.dropFirst()) : path).absoluteString,
            "Cache-Control": "no-cache",
            "Pragma": "no-cache"
        ]
        if let csrf = csrfToken(for: baseURL) {
            headers["X-XSRF-TOKEN"] = csrf
            headers["X-CSRF-TOKEN"] = csrf
        }
        return headers
    }

    private func csrfToken(for url: URL) -> String? {
        guard let cookies = cookieStorage.cookies(for: url) else {
            return nil
        }
        let raw = cookies.first(where: { ["XSRF-TOKEN", "CSRF-TOKEN", "csrftoken"].contains($0.name) })?.value
        guard let raw else { return nil }
        return raw.removingPercentEncoding ?? raw
    }

    private func ensureConsentCookies() {
        guard let host = baseURL.host else { return }
        let oneYear = Date().addingTimeInterval(365 * 24 * 60 * 60)
        let consentCookies: [HTTPCookie] = [
            .init(properties: [
                .domain: host,
                .path: "/",
                .name: "pisnotshowhint",
                .value: "true",
                .secure: "TRUE",
                .expires: oneYear
            ]),
            .init(properties: [
                .domain: host,
                .path: "/",
                .name: "piscookiewindow",
                .value: "{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}",
                .secure: "TRUE",
                .expires: oneYear
            ])
        ].compactMap { $0 }

        for cookie in consentCookies where cookieStorage.cookies?.contains(where: { $0.name == cookie.name }) != true {
            cookieStorage.setCookie(cookie)
        }
    }

    private func maybePersistWpisCookie(from response: HTTPURLResponse) {
        guard let host = baseURL.host else { return }
        guard let fields = response.allHeaderFields as? [String: String] else { return }
        let setCookieHeaders = fields.filter { $0.key.caseInsensitiveCompare("Set-Cookie") == .orderedSame }.map { $0.value }
        for header in setCookieHeaders {
            if let range = header.range(of: "WPIS=") {
                let suffix = header[range.upperBound...]
                let value = suffix.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
                guard !value.isEmpty else { continue }
                let expires = Date().addingTimeInterval(30 * 24 * 60 * 60)
                if let cookie = HTTPCookie(properties: [
                    .domain: host,
                    .path: "/",
                    .name: "WPIS",
                    .value: value,
                    .secure: "TRUE",
                    .expires: expires
                ]) {
                    cookieStorage.setCookie(cookie)
                }
            }
        }
    }

    private func urlEncode(_ input: String) -> String {
        input.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? input
    }
}
