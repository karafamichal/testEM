import Foundation

struct QrTokenPayload {
    let rawBase64: String
    let decodedBytes: Data
}

final class QRDaemonService {
    private let username: String
    private let password: String
    private let onTokenUpdate: (String, String) -> Void
    private let onError: (String) -> Void
    private let onUserName: (String) -> Void
    private let onSerialNumber: (String) -> Void
    private let onAccountInfo: (AccountDetails) -> Void
    private let onStatus: (String) -> Void

    private var sessionBaseURL: URL
    private let cookieStorage = HTTPCookieStorage()
    private let userAgent = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"

    private var lastTokenHex: String?
    private var lastTokenBase64: String?
    private var serialNumber: String
    private var nfcUid: String
    private var nfcEnabled: Bool
    private var pollingTask: Task<Void, Never>?
    private var isAuthenticated = false
    private var authFailures = 0
    private var isPolling = false

    init(
        baseURL: URL = QRDaemonConfig.baseURL,
        username: String,
        password: String,
        initialSerialNumber: String,
        initialNfcUid: String,
        initialNfcEnabled: Bool,
        onTokenUpdate: @escaping (String, String) -> Void,
        onError: @escaping (String) -> Void,
        onUserName: @escaping (String) -> Void,
        onSerialNumber: @escaping (String) -> Void,
        onAccountInfo: @escaping (AccountDetails) -> Void,
        onStatus: @escaping (String) -> Void
    ) {
        self.sessionBaseURL = baseURL
        self.username = username
        self.password = password
        self.serialNumber = initialSerialNumber
        self.nfcUid = initialNfcUid
        self.nfcEnabled = initialNfcEnabled
        self.onTokenUpdate = onTokenUpdate
        self.onError = onError
        self.onUserName = onUserName
        self.onSerialNumber = onSerialNumber
        self.onAccountInfo = onAccountInfo
        self.onStatus = onStatus
    }

    func setNfcMode(enabled: Bool, uid: String) {
        nfcEnabled = enabled
        nfcUid = uid
        onStatus(enabled ? "NFC mode enabled" : "NFC mode disabled")
    }

    func startPolling() {
        if isPolling, pollingTask?.isCancelled == false {
            onStatus("[WARNING] Polling already running - ignoring duplicate call")
            return
        }

        isPolling = true
        pollingTask?.cancel()
        pollingTask = Task(priority: .background) { [weak self] in
            guard let self else { return }
            do {
                self.onStatus("Starting polling...")
                if !self.isAuthenticated {
                    try await self.performLogin()
                }
                try await self.pollTokens()
                self.onStatus("Polling stopped unexpectedly")
            } catch is CancellationError {
                self.onStatus("Polling stopped")
            } catch {
                self.onError("Polling error: \(error.localizedDescription)")
                self.onStatus("Polling error - restarting in 5s: \(error.localizedDescription)")
                try? await Task.sleep(nanoseconds: QRDaemonConfig.retryDelayNs)
                if !Task.isCancelled {
                    self.isPolling = false
                    self.startPolling()
                }
            }
            self.isPolling = false
        }
    }

    func stopPolling() {
        isPolling = false
        pollingTask?.cancel()
        pollingTask = nil
    }

    func clearSessionCookies() {
        cookieStorage.cookies?.forEach { cookieStorage.deleteCookie($0) }
        lastTokenHex = nil
        lastTokenBase64 = nil
        isAuthenticated = false
        authFailures = 0
    }

    func refreshAccountDetails() async throws {
        let session = makeSession()
        let raw = try await performRequest(
            session: session,
            url: sessionBaseURL.appendingPathComponent("userapi/getAccountDetail"),
            method: "GET",
            additionalHeaders: standardHeaders(referer: sessionBaseURL.appendingPathComponent("account/login"), includeCSRF: false)
        )
        try parseAndEmitAccountDetails(from: raw)
    }

    func fetchCardHistory(limit: Int = 20) async throws -> [CardHistoryItem] {
        let snr = serialNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        if snr.isEmpty {
            throw NSError(domain: "testEM", code: 400, userInfo: [NSLocalizedDescriptionKey: "Card serial number is not available"])
        }

        onStatus("Loading ticket and payment history...")
        let session = makeSession()
        let url = sessionBaseURL.appendingPathComponent("cardapi/getCardHistory/\(snr)/0/\(limit)")
        let raw = try await performRequest(
            session: session,
            url: url,
            method: "GET",
            additionalHeaders: [
                "Accept": "*/*",
                "Referer": sessionBaseURL.appendingPathComponent("account").absoluteString,
                "User-Agent": userAgent,
                "X-Requested-With": "XMLHttpRequest"
            ]
        )

        guard let root = try JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw NSError(domain: "testEM", code: 500, userInfo: [NSLocalizedDescriptionKey: "History parse failed"])
        }

        let tickets = root["tickets"] as? [[String: Any]] ?? []
        let transactions = root["transactions"] as? [[String: Any]] ?? []
        let currency = readString(tickets.first ?? [:], keys: ["currencySymbol"])
        var result: [CardHistoryItem] = []

        for (index, ticket) in tickets.enumerated() {
            let saleTime = normalizeHistoryTimestamp(readInt64(ticket, keys: ["saleTime"]) * 1000)
            let ticketId = readString(ticket, keys: ["ticketSNR"]).isEmpty
                ? "ticket-\(saleTime)-\(index)"
                : readString(ticket, keys: ["ticketSNR"])
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
                    timestampMs: saleTime,
                    title: title,
                    subtitle: detailLabel + balancePart,
                    amountText: amount
                )
            )
        }

        for (index, transaction) in transactions.enumerated() {
            let createdAt = normalizeHistoryTimestamp(readInt64(transaction, keys: ["createdAt"]) * 1000)
            let type = readInt(transaction, keys: ["transactionType"])
            let changes = transaction["changes"] as? [[String: Any]] ?? []
            let subtitle = buildChangesSubtitle(changes)
            result.append(
                CardHistoryItem(
                    id: "transaction-\(createdAt)-\(index)",
                    sourceType: .transaction,
                    timestampMs: createdAt,
                    title: "Transaction #\(type)",
                    subtitle: subtitle,
                    amountText: ""
                )
            )
        }

        return result.sorted { $0.timestampMs > $1.timestampMs }
    }

    private func performLogin() async throws {
        let session = makeSession()
        do {
            onStatus("Opening base URL...")
            let (_, baseResponse) = try await session.data(for: URLRequest(url: sessionBaseURL))
            if let http = baseResponse as? HTTPURLResponse, let effectiveURL = http.url {
                sessionBaseURL = URL(string: "\(effectiveURL.scheme ?? "https")://\(effectiveURL.host ?? sessionBaseURL.host ?? "")") ?? sessionBaseURL
            }

            onStatus("Opening account page...")
            _ = try await performRequest(
                session: session,
                url: sessionBaseURL.appendingPathComponent("account"),
                method: "GET",
                additionalHeaders: [
                    "User-Agent": userAgent,
                    "Referer": sessionBaseURL.absoluteString
                ]
            )

            ensureConsentCookies()

            onStatus("Submitting login...")
            let loginBody = "post[login]=\(urlEncode(username))&post[password]=\(urlEncode(password))"
            let loginURL = sessionBaseURL.appendingPathComponent("accountapi/login")
            var loginRequest = URLRequest(url: loginURL)
            loginRequest.httpMethod = "POST"
            loginRequest.httpBody = loginBody.data(using: .utf8)
            standardHeaders(referer: sessionBaseURL.appendingPathComponent("account/login"), includeCSRF: true).forEach {
                loginRequest.setValue($0.value, forHTTPHeaderField: $0.key)
            }
            let loginCookieHeader = cookieHeader(for: loginURL)
            if !loginCookieHeader.isEmpty {
                loginRequest.setValue(loginCookieHeader, forHTTPHeaderField: "Cookie")
            }

            let (loginData, loginRawResponse) = try await session.data(for: loginRequest)
            guard let loginResponse = loginRawResponse as? HTTPURLResponse else {
                throw NSError(domain: "testEM", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid login response"])
            }
            maybePersistWpisCookie(from: loginResponse)

            guard loginResponse.statusCode == 200 else {
                throw NSError(domain: "testEM", code: loginResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Login failed: \(loginResponse.statusCode)"])
            }

            let decoded = try JSONDecoder().decode(LoginResponse.self, from: loginData)
            guard decoded.success else {
                throw NSError(domain: "testEM", code: 401, userInfo: [NSLocalizedDescriptionKey: "Login failed: API returned success=false"])
            }

            isAuthenticated = true
            authFailures = 0
            onStatus("Login successful (cookies: \((cookieStorage.cookies(for: QRDaemonConfig.tokenAPI) ?? []).count))")

            async let uidVerification: Void = verifySession(session: session)
            async let accountLoad: Void = loadAccountDetailAfterLogin(session: session)
            _ = try await (uidVerification, accountLoad)

            onStatus("Session ready")
        } catch {
            isAuthenticated = false
            onError("Login failed: \(error.localizedDescription)")
            onStatus("Login failed: \(error.localizedDescription)")
            throw error
        }
    }

    private func verifySession(session: URLSession) async throws {
        onStatus("Verifying session (getUId)...")
        let url = sessionBaseURL.appendingPathComponent("accountapi/getUId")
        let (_, response) = try await performRequestWithResponse(
            session: session,
            url: url,
            method: "GET",
            additionalHeaders: standardHeaders(referer: sessionBaseURL, includeCSRF: false)
        )
        onStatus("getUId HTTP \(response.statusCode)")
    }

    private func loadAccountDetailAfterLogin(session: URLSession) async throws {
        onStatus("Loading account detail...")
        let url = sessionBaseURL.appendingPathComponent("userapi/getAccountDetail")
        let (data, response) = try await performRequestWithResponse(
            session: session,
            url: url,
            method: "GET",
            additionalHeaders: standardHeaders(referer: sessionBaseURL.appendingPathComponent("account/login"), includeCSRF: false)
        )
        onStatus("getAccountDetail HTTP \(response.statusCode)")
        try parseAndEmitAccountDetails(from: data)
    }

    private func pollTokens() async throws {
        onStatus("Polling for token...")
        while !Task.isCancelled {
            do {
                onStatus("Fetching token...")
                guard let payload = try await fetchQRToken() else {
                    onStatus("No token yet - retrying in 1.5s")
                    try await Task.sleep(nanoseconds: QRDaemonConfig.shortRetryNs)
                    continue
                }

                if payload.decodedBytes.isEmpty || payload.rawBase64.isEmpty {
                    onStatus("No token yet - retrying in 1.5s")
                    try await Task.sleep(nanoseconds: QRDaemonConfig.shortRetryNs)
                    continue
                }

                let hex = bytesToHex(payload.decodedBytes)
                if hex != lastTokenHex || payload.rawBase64 != lastTokenBase64 {
                    lastTokenHex = hex
                    lastTokenBase64 = payload.rawBase64
                    onTokenUpdate(hex, payload.rawBase64)
                    onStatus("Token updated")
                }

                try await Task.sleep(nanoseconds: QRDaemonConfig.pollIntervalNs)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if error.localizedDescription.contains("401") || error.localizedDescription.contains("Unauthorized") {
                    isAuthenticated = false
                    authFailures += 1
                    if authFailures >= 3 {
                        onError("Authentication failed repeatedly")
                        onStatus("Authentication failed repeatedly")
                        return
                    }
                    onStatus("Session expired, re-authenticating...")
                    try await performLogin()
                } else {
                    onError("Unexpected error: \(error.localizedDescription)")
                    onStatus("Error in poll loop: \(error.localizedDescription)")
                    try await Task.sleep(nanoseconds: QRDaemonConfig.retryDelayNs)
                }
            }
        }
    }

    private func fetchQRToken() async throws -> QrTokenPayload? {
        let identifier = (nfcEnabled && !nfcUid.isEmpty) ? nfcUid : serialNumber
        if identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            onStatus("No SNR yet - waiting")
            return nil
        }

        let session = makeSession()
        let tokenBody = "post[serialnumber]=\(urlEncode(identifier))"
        let (data, response) = try await performRequestWithResponse(
            session: session,
            url: sessionBaseURL.appendingPathComponent("cardapi/getQrToken"),
            method: "POST",
            body: tokenBody,
            contentType: "application/x-www-form-urlencoded; charset=UTF-8",
            additionalHeaders: standardHeaders(referer: sessionBaseURL.appendingPathComponent("account"), includeCSRF: true)
        )

        if response.statusCode == 401 || response.url?.path.contains("/account/login") == true {
            throw NSError(domain: "testEM", code: 401, userInfo: [NSLocalizedDescriptionKey: "401 Unauthorized - Session expired"])
        }

        guard response.statusCode == 200 else {
            return nil
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let success = json["success"] as? Bool ?? false
        let dataField = json["data"] as? String ?? ""
        let base64Field = json["base64"] as? String ?? ""
        if !success || (dataField.isEmpty && base64Field.isEmpty) {
            return nil
        }

        let rawToken = base64Field.isEmpty ? dataField : base64Field
        let normalized = rawToken.replacingOccurrences(of: " ", with: "+")
        let stripped = normalized.replacingOccurrences(of: "[^A-Za-z0-9+/=_-]", with: "", options: .regularExpression)
        let padded = stripped + String(repeating: "=", count: (4 - (stripped.count % 4)) % 4)
        guard let decoded = Data(base64Encoded: padded, options: [.ignoreUnknownCharacters]) else {
            return nil
        }
        authFailures = 0
        return QrTokenPayload(rawBase64: rawToken, decodedBytes: decoded)
    }

    private func parseAndEmitAccountDetails(from raw: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw NSError(domain: "testEM", code: 500, userInfo: [NSLocalizedDescriptionKey: "Account detail parse failed"])
        }

        let data = (root["data"] as? [String: Any]) ?? root
        let user = (data["wertyzUser"] as? [String: Any]) ?? (data["user"] as? [String: Any]) ?? data
        let card = firstCard(from: user) ?? firstCard(from: data) ?? [:]
        let ticket = firstTicket(from: card) ?? [:]

        let accountName = deriveDisplayName(user: user, card: card, fallback: data)
        if !accountName.isEmpty {
            onUserName(accountName)
        }

        let cardSNR = readString(card, keys: ["snr", "cardSnr", "cardSNR", "cardNumber", "cardnumber", "serialNumber", "serialnumber"])
        let snr = cardSNR.isEmpty
            ? readString(data, keys: ["snr", "cardSnr", "cardSNR", "cardNumber", "cardnumber", "serialNumber", "serialnumber"])
            : cardSNR

        if !snr.isEmpty, snr != serialNumber {
            serialNumber = snr
            onSerialNumber(snr)
            onStatus("Loaded SNR")
        }

        let templateRaw = readString(card, keys: ["template"])
        let templateBase64 = readString(card, keys: ["base64", "cardBase64"]).isEmpty
            ? extractTemplateBase64(templateRaw)
            : readString(card, keys: ["base64", "cardBase64"])

        let details = AccountDetails(
            accountName: accountName,
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
        onAccountInfo(details)
    }

    private func performRequest(
        session: URLSession,
        url: URL,
        method: String,
        body: String? = nil,
        contentType: String? = nil,
        additionalHeaders: [String: String] = [:]
    ) async throws -> Data {
        let (data, _) = try await performRequestWithResponse(
            session: session,
            url: url,
            method: method,
            body: body,
            contentType: contentType,
            additionalHeaders: additionalHeaders
        )
        return data
    }

    private func performRequestWithResponse(
        session: URLSession,
        url: URL,
        method: String,
        body: String? = nil,
        contentType: String? = nil,
        additionalHeaders: [String: String] = [:]
    ) async throws -> (Data, HTTPURLResponse) {
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
        additionalHeaders.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }

        let cookieHeaderValue = cookieHeader(for: url)
        if !cookieHeaderValue.isEmpty {
            request.setValue(cookieHeaderValue, forHTTPHeaderField: "Cookie")
        }

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NSError(domain: "testEM", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid HTTP response"])
        }
        if httpResponse.statusCode == 401 {
            throw NSError(domain: "testEM", code: 401, userInfo: [NSLocalizedDescriptionKey: "401 Unauthorized - Session expired"])
        }
        return (data, httpResponse)
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = cookieStorage
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .always
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration)
    }

    private func standardHeaders(referer: URL, includeCSRF: Bool) -> [String: String] {
        var headers: [String: String] = [
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "X-Requested-With": "XMLHttpRequest",
            "Origin": sessionBaseURL.absoluteString,
            "Referer": referer.absoluteString,
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
            "User-Agent": userAgent
        ]
        if includeCSRF, let csrf = csrfToken(for: sessionBaseURL) {
            headers["X-XSRF-TOKEN"] = csrf
            headers["X-CSRF-TOKEN"] = csrf
        }
        return headers
    }

    private func cookieHeader(for url: URL) -> String {
        let cookies = cookieStorage.cookies(for: url) ?? []
        let sorted = cookies.sorted {
            if $0.name.caseInsensitiveCompare("WPIS") == .orderedSame { return true }
            if $1.name.caseInsensitiveCompare("WPIS") == .orderedSame { return false }
            return $0.name < $1.name
        }
        return sorted.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    private func csrfToken(for url: URL) -> String? {
        let raw = (cookieStorage.cookies(for: url) ?? []).first {
            ["XSRF-TOKEN", "CSRF-TOKEN", "csrftoken"].contains($0.name)
        }?.value
        guard let raw else { return nil }
        return raw.removingPercentEncoding ?? raw
    }

    private func ensureConsentCookies() {
        guard let host = sessionBaseURL.host else { return }
        let expires = Date().addingTimeInterval(365 * 24 * 60 * 60)
        let cookies: [HTTPCookie] = [
            .init(properties: [.domain: host, .path: "/", .name: "pisnotshowhint", .value: "true", .secure: "TRUE", .expires: expires]),
            .init(properties: [.domain: host, .path: "/", .name: "piscookiewindow", .value: "{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}", .secure: "TRUE", .expires: expires])
        ].compactMap { $0 }
        for cookie in cookies where cookieStorage.cookies?.contains(where: { $0.name == cookie.name }) != true {
            cookieStorage.setCookie(cookie)
        }
    }

    private func maybePersistWpisCookie(from response: HTTPURLResponse) {
        guard let host = sessionBaseURL.host else { return }
        let setCookieHeaders = response.allHeaderFields.compactMap { key, value -> String? in
            guard String(describing: key).caseInsensitiveCompare("Set-Cookie") == .orderedSame else { return nil }
            return String(describing: value)
        }
        for header in setCookieHeaders {
            let matches = header.components(separatedBy: ",")
            for part in matches {
                guard let range = part.range(of: "WPIS=") else { continue }
                let suffix = part[range.upperBound...]
                let value = suffix.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
                guard !value.isEmpty else { continue }
                if let cookie = HTTPCookie(properties: [
                    .domain: host,
                    .path: "/",
                    .name: "WPIS",
                    .value: value,
                    .secure: "TRUE",
                    .expires: Date().addingTimeInterval(30 * 24 * 60 * 60)
                ]) {
                    cookieStorage.setCookie(cookie)
                }
            }
        }
    }

    private func deriveDisplayName(user: [String: Any], card: [String: Any], fallback: [String: Any]) -> String {
        let cardFullName = readString(card, keys: ["fullName", "fullname", "ownerFullName", "name"])
        let cardFirstName = readString(card, keys: ["ownerFirstName", "firstName", "firstname", "first_name"])
        let cardLastName = readString(card, keys: ["ownerLastName", "lastName", "lastname", "last_name"])
        let dataFullName = readString(user, keys: ["fullName", "fullname", "name"])
        let dataFirstName = readString(user, keys: ["firstName", "firstname", "first_name"])
        let dataLastName = readString(user, keys: ["lastName", "lastname", "last_name"])

        if !cardFullName.isEmpty { return cardFullName }
        if !dataFullName.isEmpty { return dataFullName }
        let cardJoined = [cardFirstName, cardLastName].filter { !$0.isEmpty }.joined(separator: " ")
        if !cardJoined.isEmpty { return cardJoined }
        let dataJoined = [dataFirstName, dataLastName].filter { !$0.isEmpty }.joined(separator: " ")
        if !dataJoined.isEmpty { return dataJoined }
        return readString(fallback, keys: ["name", "fullName", "displayName"])
    }

    private func firstCard(from dict: [String: Any]) -> [String: Any]? {
        if let card = dict["card"] as? [String: Any] { return card }
        if let cards = dict["cards"] as? [[String: Any]], let first = cards.first { return first }
        if let user = dict["wertyzUser"] as? [String: Any] { return firstCard(from: user) }
        if let user = dict["user"] as? [String: Any] { return firstCard(from: user) }
        return nil
    }

    private func firstTicket(from card: [String: Any]) -> [String: Any]? {
        guard let tickets = card["tickets"] as? [[String: Any]] else { return nil }
        return tickets.first(where: { ($0["active"] as? Bool) == true }) ?? tickets.first
    }

    private func readString(_ dict: [String: Any], keys: [String]) -> String {
        for key in keys {
            if let value = dict[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { return trimmed }
            }
        }
        return ""
    }

    private func readInt64(_ dict: [String: Any], keys: [String]) -> Int64 {
        for key in keys {
            if let value = dict[key] as? Int64 { return value }
            if let value = dict[key] as? Int { return Int64(value) }
            if let value = dict[key] as? Double { return Int64(value) }
            if let value = dict[key] as? String, let parsed = Int64(value) { return parsed }
        }
        return 0
    }

    private func readOptionalInt64(_ dict: [String: Any], keys: [String]) -> Int64? {
        for key in keys {
            if let value = dict[key] as? Int64 { return value }
            if let value = dict[key] as? Int { return Int64(value) }
            if let value = dict[key] as? Double { return Int64(value) }
            if let value = dict[key] as? String, let parsed = Int64(value) { return parsed }
        }
        return nil
    }

    private func readInt(_ dict: [String: Any], keys: [String]) -> Int {
        Int(readInt64(dict, keys: keys))
    }

    private func readMoney(_ dict: [String: Any], keys: [String]) -> Double? {
        for key in keys {
            if let value = dict[key] as? Double {
                return value.rounded(.towardZero) == value ? value / 100.0 : value
            }
            if let value = dict[key] as? Int64 { return Double(value) / 100.0 }
            if let value = dict[key] as? Int { return Double(value) / 100.0 }
            if let value = dict[key] as? String {
                let normalized = value.replacingOccurrences(of: ",", with: ".")
                if let parsed = Double(normalized) {
                    return normalized.contains(".") ? parsed : parsed / 100.0
                }
            }
        }
        return nil
    }

    private func extractTemplateBase64(_ raw: String) -> String {
        guard !raw.isEmpty else { return "" }
        if let data = raw.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let value = object["base64"] as? String {
            return value
        }
        return ""
    }

    private func buildChangesSubtitle(_ changes: [[String: Any]]) -> String {
        let parts = changes.compactMap { readString($0, keys: ["value", "valueAfter", "valueBefore"]) }.filter { !$0.isEmpty }
        return parts.isEmpty ? "Transaction details" : parts.joined(separator: " | ")
    }

    private func formatAmount(cents: Int64, currency: String) -> String {
        let amount = String(format: "%.2f", Double(cents) / 100.0)
        return currency.isEmpty ? amount : "\(amount) \(currency)"
    }

    private func normalizeHistoryTimestamp(_ rawMs: Int64) -> Int64 {
        guard rawMs > 0 else { return rawMs }
        return rawMs - Int64(TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(rawMs) / 1000.0)) * 1000)
    }

    private func bytesToHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private func urlEncode(_ input: String) -> String {
        input.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? input
    }
}
