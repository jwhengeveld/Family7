import Foundation
import SwiftSoup

// Ports van de repositories in core/ van de Android-apps. Dezelfde selectors,
// dezelfde terugvallen; afwijkingen staan erbij.

// MARK: - Aanmelden

final class AuthRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private let defaults = UserDefaults.standard
    private static let loggedInKey = "family7.isLoggedIn"
    private static let usernameKey = "family7.username"
    private static let uidPattern = try! NSRegularExpression(pattern: #"/user/(\d+)"#)

    /// Of er lokaal een sessie bewaard staat, zonder Family7 te vragen; dan kan
    /// de app meteen het startscherm tonen en de sessie op de achtergrond bevestigen.
    var hasStoredSession: Bool { defaults.bool(forKey: Self.loggedInKey) && http.hasSessionCookie }

    func login(email: String, password: String) async throws {
        let account = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let loginURL = URL(string: "https://www.family7.nl/user/login")!
        do {
            // Stap 1: het form_build_id dat Drupal per bezoek meegeeft.
            let form = try await http.document(loginURL, referer: "https://www.family7.nl/")
            let buildId = form.one("input[name=form_build_id]")?.attribute("value") ?? ""

            // Stap 2: de aanmelding zelf.
            var fields = [("name", account), ("pass", password), ("form_id", "user_login_form"),
                          ("op", "Inloggen"), ("persistent_login", "1")]
            if !buildId.isEmpty { fields.append(("form_build_id", buildId)) }
            let (data, _) = try await http.postForm(loginURL, fields: fields)

            // Drupal meldt een verkeerde combinatie in het formulier zelf.
            let page = try SwiftSoup.parse(String(decoding: data, as: UTF8.self))
            let formError = page.joinedText(".messages--error, .messages.error, .alert-danger")
            if !formError.isEmpty { throw Family7Error(formError) }

            // Stap 3: Family7 zelf laten bevestigen wie we nu zijn.
            guard try await fetchOwnUid() != nil else {
                throw Family7Error("Inloggen mislukt. Controleer uw e-mailadres en wachtwoord.")
            }
            defaults.set(true, forKey: Self.loggedInKey)
            defaults.set(account, forKey: Self.usernameKey)
        } catch let error as URLError {
            throw Family7Error("Verbindingsfout: \(error.localizedDescription)")
        }
    }

    /// Bevestigt de bewaarde sessie bij Family7. Alleen als Family7 duidelijk
    /// zegt dat hij voorbij is, geeft dit false; een haperend netwerk niet.
    func confirmSession() async -> Bool {
        guard hasStoredSession else { return false }
        do {
            if try await fetchOwnUid() != nil { return true }
            logout()
            return false
        } catch {
            return true
        }
    }

    /// /user leidt een ingelogde bezoeker door naar /user/{uid}, een anonieme
    /// naar de aanmeldpagina.
    private func fetchOwnUid() async throws -> String? {
        let (_, response) = try await http.get(URL(string: "https://www.family7.nl/user")!)
        let path = response.url?.path ?? ""
        let range = NSRange(path.startIndex..., in: path)
        guard let match = Self.uidPattern.firstMatch(in: path, range: range),
              let uid = Range(match.range(at: 1), in: path) else { return nil }
        return String(path[uid])
    }

    func logout() {
        http.clearSession()
        defaults.removeObject(forKey: Self.loggedInKey)
        defaults.removeObject(forKey: Self.usernameKey)
    }
}

// MARK: - Catalogus

final class CatalogRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private let snapshots = SnapshotStore()
    private let defaults = UserDefaults.standard

    let homeCache = TimedCache<[CategoryRow]>(ttl: catalogTTL)
    let azCache = TimedCache<[ProgramItem]>(ttl: catalogTTL)
    let kidsCache = TimedCache<[ProgramItem]>(ttl: catalogTTL)

    private static let plusHome = URL(string: "https://www.family7.nl/plus")!
    private static let plusNew = URL(string: "https://www.family7.nl/plus/nieuw")!
    private static let plusAZ = "https://www.family7.nl/plus/a-z?title=All"
    private static let kidsURLKey = "family7.kidsURL"
    private static let suppressedRowTitles: Set<String> = ["mijn lijst", "mijn lijstje"]
    private static let kidsHints = ["kinder", "kids", "jeugd"]
    private static let maxPages = 20
    private static let cardSelector = [
        ".slider-default_element", ".more-series-on-demand_element",
        ".view-block_element-wrapper", ".view-block_element", ".views-row"
    ].joined(separator: ", ")

    /// De catalogus van de vorige sessie in de caches zetten, zonder dat die als vers telt.
    func restoreSnapshots() {
        if homeCache.snapshot == nil, let rows = snapshots.read([CategoryRow].self, "home") { homeCache.seed(rows) }
        if azCache.snapshot == nil, let items = snapshots.read([ProgramItem].self, "az") { azCache.seed(items) }
        if kidsCache.snapshot == nil, let items = snapshots.read([ProgramItem].self, "kids") { kidsCache.seed(items) }
    }

    /// Wist de cache in het geheugen en op schijf (bij uitloggen).
    func clear() {
        homeCache.clear(); azCache.clear(); kidsCache.clear()
        snapshots.clear()
    }

    // MARK: startpagina

    func onDemandHome(force: Bool = false) async throws -> [CategoryRow] {
        if !force, let fresh = homeCache.fresh { return fresh }

        // De startpagina en "Nieuw toegevoegd" tegelijk ophalen.
        async let homeDoc = http.document(Self.plusHome)
        async let newDoc: Document? = try? http.document(Self.plusNew)
        let doc = try await homeDoc
        let newest = await newDoc.map(parseCards) ?? []

        var rows: [CategoryRow] = []
        if let hero = parseHero(doc) {
            rows.append(CategoryRow(id: "uitgelicht", title: "Uitgelicht", items: [hero]))
        }
        if !newest.isEmpty {
            rows.append(CategoryRow(id: "nieuw_toegevoegd", title: "Nieuw toegevoegd",
                                    moreURL: Self.plusNew.absoluteString, items: unique(newest)))
        }
        for row in parseHomeRows(doc) where !rows.contains(where: { $0.id == row.id }) {
            rows.append(row)
        }

        // Terugval als de opmaak van de site verandert: dan tenminste alles.
        if !rows.contains(where: { !$0.items.isEmpty && $0.id != "uitgelicht" }) {
            let all = try await allPages(Self.plusAZ)
            if !all.isEmpty { rows.append(CategoryRow(id: "alle", title: "Alle programma's", items: all)) }
        }

        homeCache.put(rows)
        // Een lege uitkomst mag de laatst goede catalogus niet overschrijven.
        if rows.contains(where: { !$0.items.isEmpty }) { snapshots.write(rows, "home") }
        return rows
    }

    private func parseHero(_ doc: Document) -> ProgramItem? {
        guard let header = doc.one("section.on-demand-header") else { return nil }
        let href = header.one("a[href*='/plus/programmas/']")?.attribute("href") ?? ""
        guard !href.isEmpty else { return nil }

        // De achtergrondafbeelding staat in een inline <style>-regel.
        let style = header.all("style").map { (try? $0.html()) ?? "" }.joined()
        var background = ""
        if let range = style.range(of: #"url\('([^']+)'\)"#, options: .regularExpression) {
            background = String(style[range]).replacingOccurrences(of: "url('", with: "").replacingOccurrences(of: "')", with: "")
        }
        let slug = Family7URL.slug(href)
        return ProgramItem(
            id: href, slug: slug, title: Family7URL.titleFromSlug(slug),
            thumbnailURL: Family7URL.absolute(background), url: Family7URL.absolute(href),
            description: header.one(".introduction")?.plainText ?? "",
            nodeId: header.one("[data-node-id]")?.attribute("data-node-id") ?? ""
        )
    }

    private func parseHomeRows(_ doc: Document) -> [CategoryRow] {
        var rows: [CategoryRow] = []
        for block in doc.all("section.on-demand_home-section, .block-view") {
            let title = block.one(".block-view-header_element-title, h3, h2")?.plainText ?? ""
            if title.isEmpty || Self.suppressedRowTitles.contains(title.lowercased()) { continue }

            let items = unique(block.all(Self.cardSelector).compactMap(parseCard))
            if items.isEmpty { continue }

            let id = title.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "_", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
            if rows.contains(where: { $0.id == id }) { continue }

            let more = Family7URL.absolute(block.one(".more-link a[href], a[href*='/plus/special/']")?.attribute("href") ?? "")
            if more.contains("/plus/special/"),
               Self.kidsHints.contains(where: { (title + more).lowercased().contains($0) }) {
                defaults.set(more, forKey: Self.kidsURLKey)
            }
            rows.append(CategoryRow(id: id, title: title, moreURL: more, items: items))
        }
        return rows
    }

    // MARK: overzichten

    func kidsPrograms(force: Bool = false) async throws -> [ProgramItem] {
        if !force, let fresh = kidsCache.fresh { return fresh }
        guard let url = await resolveKidsURL() else {
            throw Family7Error("Geen kidssectie gevonden. Controleer of u bent ingelogd met een Family7 Plus-account.")
        }
        let items = try await allPages(url)
        guard !items.isEmpty else { throw Family7Error("Er zijn nu geen kinderprogramma's beschikbaar.") }
        kidsCache.put(items)
        snapshots.write(items, "kids")
        return items
    }

    private func resolveKidsURL() async -> String? {
        if let doc = try? await http.document(Self.plusHome),
           let href = doc.all("a[href*='/plus/special/']").map({ $0.attribute("href") }).first(where: { href in
               Self.kidsHints.contains { Family7URL.decodedSlug(href).lowercased().contains($0) }
           }) {
            let url = Family7URL.absolute(href)
            defaults.set(url, forKey: Self.kidsURLKey)
            return url
        }
        return defaults.string(forKey: Self.kidsURLKey)
    }

    func allPrograms(force: Bool = false) async throws -> [ProgramItem] {
        if !force, let fresh = azCache.fresh { return fresh }
        let items = try await allPages(Self.plusAZ)
        if !items.isEmpty {
            azCache.put(items)
            snapshots.write(items, "az")
        }
        return items
    }

    func programs(from url: String) async throws -> [ProgramItem] {
        try await allPages(url)
    }

    // MARK: parsen en ophalen

    private func parseCards(_ doc: Document) -> [ProgramItem] {
        unique(doc.all(Self.cardSelector).compactMap(parseCard))
    }

    private func parseCard(_ card: Element) -> ProgramItem? {
        guard let link = card.one("a[href*='/plus/programmas/'], a[href*='/programmas/']") else { return nil }
        let href = link.attribute("href")
        guard !href.isEmpty, !href.hasPrefix("#") else { return nil }
        let image = card.one("img")
        // Op de sliders staat de leesbare naam alleen in het title-attribuut van de afbeelding.
        let title = card.one(".titleProgramme, .view-block_element-title, .title, h4")?.plainText.nonEmpty
            ?? image?.attribute("title").nonEmpty
            ?? Family7URL.titleFromSlug(Family7URL.slug(href))
        return ProgramItem(
            id: href, slug: Family7URL.slug(href), title: title,
            thumbnailURL: Family7URL.absolute(image?.attribute("src") ?? ""),
            badge: card.one("[class*=ribbon], .badge, .label")?.plainText ?? "",
            url: Family7URL.absolute(href),
            nodeId: card.one("[data-node-id]")?.attribute("data-node-id") ?? ""
        )
    }

    /// Een overzichtspagina met al zijn vervolgpagina's.
    private func allPages(_ start: String) async throws -> [ProgramItem] {
        var collected: [ProgramItem] = []
        var seen = Set<String>()
        for page in 0..<Self.maxPages {
            let address = page == 0 ? start : start + (start.contains("?") ? "&" : "?") + "page=\(page)"
            guard let url = URL(string: address) else { break }
            let doc: Document
            do { doc = try await http.document(url) } catch {
                if page == 0 { throw error } else { break }
            }
            let before = collected.count
            for item in parseCards(doc) where seen.insert(item.slug).inserted { collected.append(item) }
            let hasMore = !doc.all(".pager__item--next a, li.pager-next a, a[rel=next]").isEmpty
            if collected.count == before || !hasMore { break }
        }
        return collected
    }

    private func unique(_ items: [ProgramItem]) -> [ProgramItem] {
        var seen = Set<String>()
        return items.filter { seen.insert($0.slug).inserted }
    }
}

// MARK: - Programma's en afleveringen

final class VideoRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private let lock = NSLock()
    private var details: [String: ProgramDetail] = [:]
    /// Kort onthouden stream-adressen, zodat een vooraf opgezocht adres meteen
    /// afspeelt; kort, omdat het token van Streampartner verloopt.
    private var streams: [String: TimedCache<String>] = [:]
    private static let streamTTL: TimeInterval = 2 * 60

    func cachedDetail(_ slug: String) -> ProgramDetail? { lock.withLock { details[slug] } }

    func programDetail(_ slug: String) async throws -> ProgramDetail {
        let address = slug.hasPrefix("http") ? slug : "https://www.family7.nl/plus/programmas/\(slug)"
        guard let url = URL(string: address) else { throw Family7Error("Ongeldig programma-adres.") }
        let doc = try await http.document(url)

        let title = doc.one("meta[property=og:title]")?.attribute("content").nonEmpty
            ?? ((try? doc.title()) ?? "").components(separatedBy: "|").first?.trimmingCharacters(in: .whitespaces).nonEmpty
            ?? Family7URL.titleFromSlug(slug)
        let poster = Family7URL.absolute(doc.one(".video-page-top-content img, .series-page-image img, .main-image img")?.attribute("src") ?? "")
        let myListButton = doc.one(".process-to-my-series-list, [data-node-id]")
        var seen = Set<String>()
        let episodes = doc.all(".view-block_element-wrapper, .view-block_element")
            .compactMap { parseEpisode($0, fallbackThumb: poster) }
            .filter { seen.insert($0.videoSlug).inserted }

        let seasonSelect = doc.one(".more-videos_season-select")
        let selected = seasonSelect?.one("option[selected]")
        let firstOption = seasonSelect?.one("option")
        let seasonNumber = selected?.attribute("value").nonEmpty ?? firstOption?.attribute("value").nonEmpty ?? "1"
        let seasonLabel = selected?.plainText.nonEmpty ?? firstOption?.plainText.nonEmpty ?? "Afleveringen"

        let detail = ProgramDetail(
            slug: slug, title: title, posterURL: poster,
            description: doc.one(".introduction, .series-page-description, .field--name-body")?.plainText ?? "",
            category: doc.one(".series-info")?.plainText ?? "",
            seasons: episodes.isEmpty ? [] : [SeasonInfo(seasonNumber: seasonNumber, title: seasonLabel, episodes: episodes)],
            nodeId: myListButton?.attribute("data-node-id") ?? "",
            isInMyList: myListButton?.hasClass("added") ?? false
        )
        lock.withLock { details[slug] = detail }
        return detail
    }

    /// Een afleveringskaart: nummer in .video-number, titel links en speelduur rechts in .video-title.
    private func parseEpisode(_ card: Element, fallbackThumb: String) -> EpisodeItem? {
        guard let link = card.one("a[href*='/video/']") else { return nil }
        let href = link.attribute("href")
        let slug = Family7URL.slug(href)
        guard !slug.isEmpty else { return nil }
        let titleBlock = card.one(".video-title")
        let title = titleBlock?.one(".float-left")?.plainText.nonEmpty
            ?? titleBlock?.ownText().trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            ?? card.one(".view-block_element-title")?.plainText.nonEmpty
            ?? "Aflevering"
        // Het adres van een aflevering heeft de vorm {seizoen}-{nummer}-{slug}.
        let parts = slug.split(separator: "-")
        let numberFromSlug = parts.count > 1 && parts[1].allSatisfy(\.isNumber) ? String(parts[1]) : ""
        let thumb = Family7URL.absolute(card.one(".view-block_element-thumbnail > img")?.attribute("src") ?? "")
        return EpisodeItem(
            id: slug,
            episodeNumber: card.one(".video-number")?.plainText.nonEmpty ?? numberFromSlug,
            title: title,
            description: card.one(".video-description")?.plainText ?? "",
            duration: titleBlock?.one(".float-right")?.plainText ?? "",
            thumbnailURL: thumb.isEmpty ? fallbackThumb : thumb,
            videoSlug: slug,
            videoURL: Family7URL.absolute(href)
        )
    }

    /// Alvast opzoeken; een mislukking is hier geen fout.
    func prefetchStreamURL(_ videoSlug: String) async {
        _ = try? await streamURL(videoSlug)
    }

    func streamURL(_ videoSlug: String, forceFresh: Bool = false) async throws -> String {
        let cache = lock.withLock {
            if let existing = streams[videoSlug] { return existing }
            let created = TimedCache<String>(ttl: Self.streamTTL)
            streams[videoSlug] = created
            return created
        }
        if !forceFresh, let fresh = cache.fresh { return fresh }
        let url = try await fetchStreamURL(videoSlug)
        cache.put(url)
        return url
    }

    private func fetchStreamURL(_ videoSlug: String) async throws -> String {
        let address = videoSlug.hasPrefix("http") ? videoSlug : "https://www.family7.nl/video/\(videoSlug)"
        guard let url = URL(string: address) else { throw Family7Error("Ongeldig video-adres.") }
        let (data, _) = try await http.get(url, referer: "https://www.family7.nl/plus")
        let html = String(decoding: data, as: UTF8.self)
        let doc = try SwiftSoup.parse(html, address)

        var player = doc.firstAttribute(".video-player--loader, .video-player--frame", "data-src")
        if player.isEmpty { player = doc.firstAttribute("iframe[src*='player.php']", "src") }
        if !player.isEmpty, let playerURL = URL(string: Family7URL.absolute(player)) {
            let (playerData, _) = try await http.get(playerURL, referer: "https://www.family7.nl")
            let stream = StreampartnerPlayer.streamURL(fromPlayerHTML: String(decoding: playerData, as: UTF8.self))
            if !stream.isEmpty { return stream }
        }
        // Terugval: het adres staat soms gewoon op de pagina zelf.
        let direct = StreampartnerPlayer.firstM3u8(html)
        if !direct.isEmpty { return direct }
        throw Family7Error("Kon geen afspeelbare videobron vinden voor deze aflevering.")
    }
}

// MARK: - Live

final class LiveRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private let defaults = UserDefaults.standard
    private static let livePage = URL(string: "https://www.family7.nl/plus/live")!
    private static let lastPlayerKey = "family7.live.lastPlayerURL"
    private static let lastStreamKey = "family7.live.lastStreamURL"

    /// Het programma van nu en het stream-adres. De laatst werkende speler- en
    /// stream-adressen worden onthouden als noodgreep, omdat Streampartner
    /// regelmatig van host wisselt.
    func liveInfo() async throws -> LiveStreamInfo {
        let (data, response) = try await http.get(Self.livePage, referer: "https://www.family7.nl/plus")
        if response.statusCode == 401 || response.statusCode == 403 { throw UnauthorizedError() }
        let html = String(decoding: data, as: UTF8.self)
        let doc = try SwiftSoup.parse(html, Self.livePage.absoluteString)

        let player = discoverPlayerURL(doc, html: html)
        if !player.isEmpty { defaults.set(player, forKey: Self.lastPlayerKey) }

        var stream = await resolveStream(player)
        if stream.isEmpty { stream = await resolveStream(defaults.string(forKey: Self.lastPlayerKey) ?? "") }
        if stream.isEmpty { stream = StreampartnerPlayer.firstM3u8(html) }
        if stream.isEmpty { stream = defaults.string(forKey: Self.lastStreamKey) ?? "" }
        if !stream.isEmpty { defaults.set(stream, forKey: Self.lastStreamKey) }

        return LiveStreamInfo(
            title: "Family7 Live TV",
            currentProgram: doc.joinedText(".tv-guide-teaser_title, .tv-guide-teaser h2, .tv-guide-teaser h3").nonEmpty ?? "Family7 Live Uitzending",
            timeRange: doc.joinedText(".tv-guide-teaser_time, .tv-guide-teaser--info-first p:nth-child(2)"),
            imageURL: Family7URL.absolute(doc.firstAttribute(".tv-guide-teaser--image img", "src")),
            description: doc.joinedText(".tv-guide-teaser_description, .tv-guide-teaser--info-second p"),
            streamURL: stream
        )
    }

    private func discoverPlayerURL(_ doc: Document, html: String) -> String {
        var candidates = [
            doc.firstAttribute(".video-player--loader, .video-player--frame", "data-src"),
            doc.firstAttribute("[data-src*='player']", "data-src"),
            doc.firstAttribute("iframe[src*='player']", "src"),
            doc.firstAttribute("iframe[src*='streampartner']", "src"),
            doc.firstAttribute("iframe[src]", "src")
        ]
        if let range = html.range(of: #"https?://[^\s"'<>]*streampartner\.nl/[^\s"'<>]+"#, options: .regularExpression) {
            candidates.append(String(html[range]))
        }
        return candidates.first { !$0.isEmpty }.map(Family7URL.absolute) ?? ""
    }

    private func resolveStream(_ player: String) async -> String {
        guard !player.isEmpty, let url = URL(string: player),
              let (data, _) = try? await http.get(url, referer: "https://www.family7.nl/") else { return "" }
        return StreampartnerPlayer.decodeStreamURLs(String(decoding: data, as: UTF8.self)).first ?? ""
    }
}

// MARK: - Mijn lijst

/// "Mijn lijst" van het account: dezelfde lijst als op de website.
final class MyListRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private static let myList = "https://www.family7.nl/plus/mijnlijst"

    func fetch() async throws -> [ProgramItem] {
        let doc: Document
        do {
            doc = try await http.document(URL(string: Self.myList)!)
        } catch is UnauthorizedError {
            throw Family7Error("Log in om uw lijst te zien.")
        }
        var seen = Set<String>()
        return doc.all(".view-block_element-wrapper, .view-block_element, .slider-default_element").compactMap { card -> ProgramItem? in
            guard let href = card.one("a[href*='/plus/programmas/']")?.attribute("href") else { return nil }
            let image = card.one("img")
            let slug = Family7URL.slug(href)
            return ProgramItem(
                id: href, slug: slug,
                title: image?.attribute("title").nonEmpty ?? Family7URL.titleFromSlug(slug),
                thumbnailURL: Family7URL.absolute(image?.attribute("src") ?? ""),
                url: Family7URL.absolute(href),
                nodeId: card.one("[data-node-id]")?.attribute("data-node-id") ?? ""
            )
        }.filter { seen.insert($0.slug).inserted }
    }

    /// Via hetzelfde eindpunt als de knop op de website. Geeft terug of het programma er nu in staat.
    func setInList(nodeId: String, add: Bool) async throws -> Bool {
        guard !nodeId.isEmpty, let url = URL(string: "\(Self.myList)/\(nodeId)/\(add ? "add" : "remove")") else {
            throw Family7Error("Dit programma heeft geen node-id.")
        }
        let (data, response) = try await http.get(url, referer: "https://www.family7.nl/plus",
                                                  headers: ["X-Requested-With": "XMLHttpRequest"])
        guard (200..<300).contains(response.statusCode) else {
            throw Family7Error("Kon de lijst niet bijwerken (\(response.statusCode)).")
        }
        // De site antwoordt met {"status":"added"} of {"status":"removed"}.
        let status = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["status"] as? String
        switch status {
        case "added": return true
        case "removed": return false
        default: return add
        }
    }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
