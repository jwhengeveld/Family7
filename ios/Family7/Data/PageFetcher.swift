import Foundation
import SwiftSoup

extension Notification.Name {
    /// Family7 gaf een anonieme pagina of de inlogpagina terug terwijl er een
    /// sessie had moeten zijn; de app laat de sessie dan bevestigen.
    static let family7SessionExpired = Notification.Name("family7SessionExpired")
}

struct SessionExpiredError: LocalizedError {
    var errorDescription: String? { "Uw sessie bij Family7 is verlopen. Log opnieuw in." }
}

/// Haalt pagina's van family7.nl op, slim en voorzichtig (port van PageFetcher.kt):
///
/// - gelijktijdige verzoeken voor hetzelfde adres delen één download;
/// - een pagina blijft een halve minuut in het geheugen, behalve als er vers
///   gevraagd wordt (verversen, programmapagina's): dan altijd naar de site;
/// - een verlopen sessie wordt herkend en gemeld, in plaats van dat een
///   anonieme pagina als lege catalogus wordt gelezen.
///
/// Alles gebeurt lokaal op het toestel; de enige bron is family7.nl.
actor PageFetcher {
    static let shared = PageFetcher()

    struct Page: Sendable {
        let html: String
        let finalURL: String
        let code: Int
    }

    private let http = Family7HTTP.shared
    private var inFlight: [String: Task<Page, Error>] = [:]
    private var recent: [String: (at: Date, page: Page)] = [:]
    private static let memo: TimeInterval = 30

    func page(_ url: String, referer: String = "https://www.family7.nl/plus",
              maxAge: TimeInterval = memo, fresh: Bool = false) async throws -> Page {
        if !fresh, let hit = recent[url], Date().timeIntervalSince(hit.at) < maxAge { return hit.page }
        // Een verzoek dat al loopt is vers genoeg om te delen.
        let task = inFlight[url] ?? Task { [http] in
            guard let target = URL(string: url) else { throw Family7Error("Ongeldig adres.") }
            let (data, response) = try await http.get(target, referer: referer, fresh: fresh)
            return Page(html: String(decoding: data, as: UTF8.self),
                        finalURL: response.url?.absoluteString ?? url, code: response.statusCode)
        }
        inFlight[url] = task
        defer { inFlight[url] = nil }
        let page = try await task.value
        recent[url] = (Date(), page)
        return page
    }

    /// De pagina als document, met de sessiecontroles van [page].
    func document(_ url: String, referer: String = "https://www.family7.nl/plus",
                  maxAge: TimeInterval = memo, fresh: Bool = false) async throws -> Document {
        let page = try await page(url, referer: referer, maxAge: maxAge, fresh: fresh)
        if page.code == 401 || page.code == 403 {
            recent[url] = nil
            if http.hasSessionCookie { Self.reportExpired() }
            throw UnauthorizedError()
        }
        let doc = try SwiftSoup.parse(page.html, page.finalURL)
        let path = URL(string: page.finalURL)?.path ?? ""
        if Family7Parser.isLoginPage(doc, finalPath: path)
            || (http.hasSessionCookie && Family7Parser.isAnonymous(doc) && Family7Parser.requiresLogin(url)) {
            recent[url] = nil
            Self.reportExpired()
            throw SessionExpiredError()
        }
        return doc
    }

    /// Voor publiek leesbare pagina's: anoniem terwijl er een sessiecookie meeging is een verlopen sessie.
    func checkSession(_ doc: Document) throws {
        if http.hasSessionCookie && Family7Parser.isAnonymous(doc) {
            Self.reportExpired()
            throw SessionExpiredError()
        }
    }

    func clear() {
        recent.removeAll()
    }

    private static func reportExpired() {
        Task { @MainActor in NotificationCenter.default.post(name: .family7SessionExpired, object: nil) }
    }
}

/// Wacht verdachte uitkomsten af tot de site ze bevestigt (zie Plausibility).
/// De site is de bron van waarheid: een eenmalige hapering houdt de vorige
/// versie tegen, maar dezelfde uitkomst een tweede keer na elkaar wint.
final class PlausibilityGate: @unchecked Sendable {
    static let shared = PlausibilityGate()
    private let lock = NSLock()
    private var pending: [String: Int] = [:]

    func accept(_ key: String, previous: Int?, new: Int) -> Bool {
        lock.withLock {
            if Plausibility.acceptable(previous: previous, new: new) {
                pending[key] = nil
                return true
            }
            if let earlier = pending[key], abs(earlier - new) <= max(2, earlier / 10) {
                pending[key] = nil
                return true
            }
            pending[key] = new
            return false
        }
    }
}
