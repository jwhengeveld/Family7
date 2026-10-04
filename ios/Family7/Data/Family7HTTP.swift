import Foundation
import Security
import SwiftSoup

/// Bewaart de aanmeldsessie (de cookies van family7.nl) in de Keychain, alleen
/// op dit toestel en pas leesbaar na de eerste ontgrendeling. De cookies staan
/// dus nooit onbeschermd op schijf en gaan niet mee in een back-up; dezelfde
/// afweging als de AndroidKeyStore in de Android-apps.
struct CookieVault: Sendable {
    private let service = "com.xiappdesign.family7.session"
    private let account = "cookies"

    func load() -> [HTTPCookie] {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let list = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [[String: Any]]
        else { return [] }
        return list.compactMap { properties in
            HTTPCookie(properties: Dictionary(uniqueKeysWithValues: properties.map { (HTTPCookiePropertyKey($0.key), $0.value) }))
        }.filter { ($0.expiresDate ?? .distantFuture) > Date() }
    }

    func save(_ cookies: [HTTPCookie]) {
        let list: [[String: Any]] = cookies.compactMap { cookie in
            cookie.properties.map { Dictionary(uniqueKeysWithValues: $0.map { ($0.key.rawValue, $0.value) }) }
        }
        guard let data = try? PropertyListSerialization.data(fromPropertyList: list, format: .binary, options: 0) else { return }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        if SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(baseQuery.merging(attributes) { $1 } as CFDictionary, nil)
        }
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
}

/// De ene HTTP-client van de app.
///
/// - Cookies leven in het geheugen (ephemeral) en worden na elk antwoord in de
///   Keychain bijgewerkt.
/// - Een mislukt GET-verzoek door een netwerkfout krijgt twee herkansingen met
///   een korte pauze: wifi of 4G die net terugkomt hoort geen foutscherm op te leveren.
/// - Een bovengrens per verzoek, zodat een scherm nooit eindeloos laadt.
final class Family7HTTP: @unchecked Sendable {
    static let shared = Family7HTTP()

    static let baseURL = URL(string: "https://www.family7.nl")!

    private static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private static let maxAttempts = 3

    let session: URLSession
    private let vault = CookieVault()
    private let cookies: HTTPCookieStorage

    private init() {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieAcceptPolicy = .always
        config.httpShouldSetCookies = true
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        config.httpAdditionalHeaders = ["User-Agent": Self.userAgent]
        // Een ephemeral sessie heeft een eigen opslag in het geheugen.
        cookies = config.httpCookieStorage ?? HTTPCookieStorage.shared
        session = URLSession(configuration: config)
        vault.load().forEach { cookies.setCookie($0) }
    }

    /// Haalt een pagina op. `referer` standaard de voorpagina van Family7.
    func get(_ url: URL, referer: String = "https://www.family7.nl/", headers: [String: String] = [:],
             fresh: Bool = false) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        // Vers betekent vers: geen antwoord uit een cache.
        if fresh { request.cachePolicy = .reloadIgnoringLocalCacheData }
        request.setValue(referer, forHTTPHeaderField: "Referer")
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }

        var lastError: Error?
        for attempt in 0..<Self.maxAttempts {
            try Task.checkCancellation()
            do {
                return try await perform(request)
            } catch let error as URLError where Self.isTransient(error) {
                lastError = error
                if attempt < Self.maxAttempts - 1 {
                    try await Task.sleep(nanoseconds: UInt64(300_000_000 * (attempt + 1)))
                }
            }
        }
        throw lastError ?? URLError(.unknown)
    }

    func postForm(_ url: URL, fields: [(String, String)]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue(url.absoluteString, forHTTPHeaderField: "Referer")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        request.httpBody = fields
            .map { "\($0.0.addingPercentEncoding(withAllowedCharacters: allowed)!)=\($0.1.addingPercentEncoding(withAllowedCharacters: allowed)!)" }
            .joined(separator: "&")
            .data(using: .utf8)
        return try await perform(request)
    }

    /// Een pagina als HTML-document. 401/403 betekent: niet (meer) ingelogd.
    func document(_ url: URL, referer: String = "https://www.family7.nl/plus") async throws -> Document {
        let (data, response) = try await get(url, referer: referer)
        if response.statusCode == 401 || response.statusCode == 403 { throw UnauthorizedError() }
        return try SwiftSoup.parse(String(decoding: data, as: UTF8.self), url.absoluteString)
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        persistCookies()
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    // MARK: - sessie

    var hasSessionCookie: Bool {
        (cookies.cookies ?? []).contains { $0.name.hasPrefix("SSESS") || $0.name.hasPrefix("SESS") }
    }

    func clearSession() {
        (cookies.cookies ?? []).forEach { cookies.deleteCookie($0) }
        vault.clear()
    }

    private func persistCookies() {
        let family7 = (cookies.cookies ?? []).filter { $0.domain.hasSuffix("family7.nl") }
        vault.save(family7)
    }

    private static func isTransient(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .resourceUnavailable:
            return true
        default:
            return false
        }
    }
}

struct UnauthorizedError: LocalizedError {
    var errorDescription: String? { "Niet ingelogd" }
}

// MARK: - SwiftSoup zonder try op elke regel

extension Element {
    func all(_ query: String) -> [Element] { (try? select(query).array()) ?? [] }
    func one(_ query: String) -> Element? { try? select(query).first() }
    func attribute(_ name: String) -> String { ((try? attr(name)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
    var plainText: String { ((try? text()) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Zoals `Elements.attr` in Jsoup: de waarde van het eerste element dat het attribuut heeft.
    func firstAttribute(_ query: String, _ name: String) -> String {
        all(query).lazy.map { $0.attribute(name) }.first { !$0.isEmpty } ?? ""
    }
    /// De tekst van alle elementen samen, zoals `Elements.text()` in Jsoup.
    func joinedText(_ query: String) -> String {
        all(query).map(\.plainText).filter { !$0.isEmpty }.joined(separator: " ")
    }
}

enum Family7URL {
    static func absolute(_ url: String) -> String {
        if url.isEmpty { return "" }
        if url.hasPrefix("http") { return url }
        if url.hasPrefix("/") { return "https://www.family7.nl\(url)" }
        return "https://www.family7.nl/\(url)"
    }

    static func slug(_ url: String) -> String {
        let path = url.components(separatedBy: "?").first ?? url
        return path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).components(separatedBy: "/").last ?? ""
    }

    static func decodedSlug(_ url: String) -> String {
        let raw = slug(url)
        return raw.removingPercentEncoding ?? raw
    }

    static func titleFromSlug(_ slug: String) -> String {
        decodedSlug(slug)
            .replacingOccurrences(of: "-", with: " ")
            .split(separator: " ")
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }
}
