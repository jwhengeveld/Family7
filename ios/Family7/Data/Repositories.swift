import Foundation
import SwiftSoup

// Ports van de repositories in core/ van de Android-apps. Het lezen van de
// pagina's zit in Family7Parser (met vangnetten voor een verbouwde site), het
// ophalen in PageFetcher (samenvoegen, korte cache, sessieherkenning).
//
// De site is de bron van waarheid: alle caching is lokaal en dient alleen om
// meteen iets te tonen terwijl de verse versie van family7.nl binnenkomt.

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
    private let pages = PageFetcher.shared
    private let snapshots = SnapshotStore()
    private let defaults = UserDefaults.standard

    let homeCache = TimedCache<[CategoryRow]>(ttl: catalogTTL)
    let azCache = TimedCache<[ProgramItem]>(ttl: catalogTTL)
    let kidsCache = TimedCache<[ProgramItem]>(ttl: catalogTTL)

    private static let plusHome = "https://www.family7.nl/plus"
    private static let plusNew = "https://www.family7.nl/plus/nieuw"
    private static let plusAZ = "https://www.family7.nl/plus/a-z?title=All"
    private static let kidsURLKey = "family7.kidsURL"
    private static let maxPages = 20
    private static let pageConcurrency = 4

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
        Task { await pages.clear() }
    }

    /// Bewaart een nieuwe uitkomst, tenzij hij verdacht mager is en de site dat
    /// nog niet bevestigd heeft: dan blijft de vorige (niet als vers) staan.
    private func keepBest<T: Codable>(_ cache: TimedCache<[T]>, _ fresh: [T], snapshot name: String,
                                      size: ([T]) -> Int = { $0.count }) -> [T] {
        if let previous = cache.snapshot,
           !PlausibilityGate.shared.accept(name, previous: size(previous), new: size(fresh)) {
            return previous
        }
        cache.put(fresh)
        if size(fresh) > 0 { snapshots.write(fresh, name) }
        return fresh
    }

    // MARK: startpagina

    func onDemandHome(force: Bool = false) async throws -> [CategoryRow] {
        if !force, let fresh = homeCache.fresh { return fresh }

        // De startpagina en "Nieuw toegevoegd" tegelijk ophalen.
        async let homeDoc = pages.document(Self.plusHome, fresh: force)
        async let newDoc: Document? = try? pages.document(Self.plusNew, fresh: force)
        let doc = try await homeDoc
        let newest = await newDoc.map(Family7Parser.programCards) ?? []

        var rows: [CategoryRow] = []
        if let hero = Family7Parser.hero(doc) {
            rows.append(CategoryRow(id: "uitgelicht", title: "Uitgelicht", items: [hero]))
        }
        if !newest.isEmpty {
            rows.append(CategoryRow(id: "nieuw_toegevoegd", title: "Nieuw toegevoegd", moreURL: Self.plusNew, items: newest))
        }
        let homeRows = Family7Parser.homeRows(doc)
        for row in homeRows where !rows.contains(where: { $0.id == row.id }) { rows.append(row) }
        if let kids = homeRows.first(where: { row in
            row.moreURL.contains("/plus/special/") &&
                Family7Parser.kidsHints.contains { (row.title + row.moreURL).lowercased().contains($0) }
        }) {
            defaults.set(kids.moreURL, forKey: Self.kidsURLKey)
        }

        // Terugval als de rijen niet meer te lezen zijn: dan tenminste alles.
        if !rows.contains(where: { !$0.items.isEmpty && $0.id != "uitgelicht" }) {
            let all = try await allPages(Self.plusAZ, fresh: force)
            if !all.isEmpty { rows.append(CategoryRow(id: "alle", title: "Alle programma's", items: all)) }
        }
        return keepBest(homeCache, rows, snapshot: "home") { $0.reduce(0) { $0 + $1.items.count } }
    }

    // MARK: overzichten

    func kidsPrograms(force: Bool = false) async throws -> [ProgramItem] {
        if !force, let fresh = kidsCache.fresh { return fresh }
        guard let url = await resolveKidsURL() else {
            throw Family7Error("Geen kidssectie gevonden. Controleer of u bent ingelogd met een Family7 Plus-account.")
        }
        let items = keepBest(kidsCache, try await allPages(url, fresh: force), snapshot: "kids")
        guard !items.isEmpty else { throw Family7Error("Er zijn nu geen kinderprogramma's beschikbaar.") }
        return items
    }

    private func resolveKidsURL() async -> String? {
        if let doc = try? await pages.document(Self.plusHome),
           let href = doc.all("a[href*='/plus/special/']").map({ $0.attribute("href") }).first(where: { href in
               Family7Parser.kidsHints.contains { Family7URL.decodedSlug(href).lowercased().contains($0) }
           }) {
            let url = Family7URL.absolute(href)
            defaults.set(url, forKey: Self.kidsURLKey)
            return url
        }
        return defaults.string(forKey: Self.kidsURLKey)
    }

    func allPrograms(force: Bool = false) async throws -> [ProgramItem] {
        if !force, let fresh = azCache.fresh { return fresh }
        return keepBest(azCache, try await allPages(Self.plusAZ, fresh: force), snapshot: "az")
    }

    /// Een overzicht wordt bij elk bezoek opnieuw bij de site opgehaald.
    func programs(from url: String) async throws -> [ProgramItem] {
        try await allPages(url, fresh: true)
    }

    /// Een overzichtspagina met al zijn vervolgpagina's: tegelijk als de pager
    /// de laatste pagina noemt, anders één voor één tot er niets nieuws bijkomt.
    private func allPages(_ start: String, fresh: Bool) async throws -> [ProgramItem] {
        let first = try await pages.document(start, fresh: fresh)
        var collected: [ProgramItem] = []
        var seen = Set<String>()
        func add(_ doc: Document) -> Int {
            let before = collected.count
            for item in Family7Parser.programCards(doc) where seen.insert(item.slug).inserted { collected.append(item) }
            return collected.count - before
        }
        _ = add(first)
        guard Family7Parser.hasNextPage(first) else { return collected }

        func url(_ page: Int) -> String { start + (start.contains("?") ? "&" : "?") + "page=\(page)" }

        if let last = Family7Parser.lastPageNumber(first) {
            let numbers = Array(1...min(last, Self.maxPages - 1))
            var docs: [Int: Document] = [:]
            for chunk in stride(from: 0, to: numbers.count, by: Self.pageConcurrency) {
                try await withThrowingTaskGroup(of: (Int, Document?).self) { group in
                    for page in numbers[chunk..<min(chunk + Self.pageConcurrency, numbers.count)] {
                        group.addTask { [pages] in (page, try? await pages.document(url(page), fresh: fresh)) }
                    }
                    for try await (page, doc) in group { docs[page] = doc }
                }
            }
            for page in numbers { if let doc = docs[page] { _ = add(doc) } }
            return collected
        }

        var doc = first
        for page in 1..<Self.maxPages where Family7Parser.hasNextPage(doc) {
            guard let next = try? await pages.document(url(page), fresh: fresh) else { break }
            doc = next
            if add(next) == 0 { break }
        }
        return collected
    }
}

// MARK: - Programma's en afleveringen

final class VideoRepository: @unchecked Sendable {
    private let pages = PageFetcher.shared
    private let snapshots = SnapshotStore()
    private let lock = NSLock()
    private var details: [String: ProgramDetail] = [:]
    private var streams: [String: TimedCache<String>] = [:]
    /// Zoveel seizoenen tegelijk; series als "Bijbelse karakters" hebben er dertien.
    private static let seasonConcurrency = 4

    /// Het laatst bekende programma: uit het geheugen, of van schijf uit een vorige sessie.
    func cachedDetail(_ slug: String) -> ProgramDetail? {
        if let detail = lock.withLock({ details[slug] }) { return detail }
        guard let detail = snapshots.read(ProgramDetail.self, Self.key(slug)) else { return nil }
        lock.withLock { details[slug] = details[slug] ?? detail }
        return detail
    }

    /// De programmapagina met alle seizoenen. De pagina bevat alleen het
    /// gekozen seizoen; de andere haalt de site (en dus ook de app) op via
    /// /get-videos-by-season/{node}/{seizoen}, hier tegelijk en begrensd.
    func programDetail(_ slug: String) async throws -> ProgramDetail {
        let address = slug.hasPrefix("http") ? slug : "https://www.family7.nl/plus/programmas/\(slug)"
        // Altijd van de site: nieuwe afleveringen moeten er meteen bij staan.
        let doc = try await pages.document(address, fresh: true)
        var detail = Family7Parser.programDetailBase(doc, slug: slug)
        let onPage = Family7Parser.episodes(doc, fallbackThumb: detail.posterURL)
        let options = Family7Parser.seasonOptions(doc)

        if options.count <= 1 || detail.nodeId.isEmpty {
            if !onPage.isEmpty {
                detail.seasons = [SeasonInfo(seasonNumber: options.first?.number ?? "1",
                                             title: options.first?.title ?? "Afleveringen", episodes: onPage)]
            }
        } else {
            let shown = options.first(where: \.selected) ?? options[0]
            let previous = cachedDetail(slug)
            var loaded: [String: [EpisodeItem]] = [shown.number: onPage]
            let others = options.filter { $0.number != shown.number || onPage.isEmpty }
            for chunk in stride(from: 0, to: others.count, by: Self.seasonConcurrency) {
                await withTaskGroup(of: (String, [EpisodeItem]?).self) { group in
                    for option in others[chunk..<min(chunk + Self.seasonConcurrency, others.count)] {
                        group.addTask { [self] in
                            (option.number, try? await seasonEpisodes(detail.nodeId, option.number, fallback: detail.posterURL))
                        }
                    }
                    for await (number, episodes) in group {
                        // Een mislukt seizoen kost alleen dat seizoen.
                        loaded[number] = episodes ?? previous?.seasons.first { $0.seasonNumber == number }?.episodes ?? []
                    }
                }
            }
            detail.seasons = options.compactMap { option in
                let episodes = loaded[option.number] ?? []
                return episodes.isEmpty ? nil : SeasonInfo(seasonNumber: option.number, title: option.title, episodes: episodes)
            }
        }

        detail.category = Family7Parser.seasonCountLabel(detail.category, seasonCount: detail.seasons.count)

        // Een eenmalig verdacht magere pagina vervangt geen goede versie; de
        // site bevestigt of corrigeert dat bij de volgende keer.
        let count = detail.seasons.reduce(0) { $0 + $1.episodes.count }
        if let previous = cachedDetail(slug),
           !PlausibilityGate.shared.accept("detail:\(slug)", previous: previous.seasons.reduce(0) { $0 + $1.episodes.count }, new: count) {
            return previous
        }
        lock.withLock { details[slug] = detail }
        if count > 0 { snapshots.write(detail, Self.key(slug)) }
        return detail
    }

    private func seasonEpisodes(_ nodeId: String, _ season: String, fallback: String) async throws -> [EpisodeItem] {
        let page = try await pages.page("https://www.family7.nl/get-videos-by-season/\(nodeId)/\(season)", fresh: true)
        guard let json = try JSONSerialization.jsonObject(with: Data(page.html.utf8)) as? [String: Any],
              let html = json["renderedItems"] as? String else { return [] }
        return Family7Parser.episodes(try SwiftSoup.parseBodyFragment(html, "https://www.family7.nl"), fallbackThumb: fallback)
    }

    private static func key(_ slug: String) -> String {
        "detail_" + String(UInt(bitPattern: slug.utf8.reduce(5381) { ($0 << 5) &+ $0 &+ Int($1) }), radix: 16)
    }

    // MARK: streams

    /// Alvast opzoeken; een mislukking is hier geen fout.
    func prefetchStreamURL(_ videoSlug: String) async {
        _ = try? await streamURL(videoSlug)
    }

    /// Het stream-adres, onthouden zo lang het token in het adres geldig is.
    func streamURL(_ videoSlug: String, forceFresh: Bool = false) async throws -> String {
        if !forceFresh, let cached = lock.withLock({ streams[videoSlug]?.fresh }) { return cached }
        let url = try await fetchStreamURL(videoSlug)
        let lifetime = StreamURLLifetime.validFor(url)
        lock.withLock {
            if lifetime > 0 {
                let cache = TimedCache<String>(ttl: lifetime)
                cache.put(url)
                streams[videoSlug] = cache
            } else {
                streams[videoSlug] = nil
            }
        }
        return url
    }

    private func fetchStreamURL(_ videoSlug: String) async throws -> String {
        let address = videoSlug.hasPrefix("http") ? videoSlug : "https://www.family7.nl/video/\(videoSlug)"
        let page = try await pages.page(address, fresh: true)
        let doc = try SwiftSoup.parse(page.html, page.finalURL)

        let player = Family7Parser.playerURL(doc, html: page.html)
        if !player.isEmpty {
            let playerPage = try await pages.page(player, referer: "https://www.family7.nl", fresh: true)
            let stream = StreampartnerPlayer.streamURL(fromPlayerHTML: playerPage.html)
            if !stream.isEmpty { return stream }
        }
        // Terugval: het adres staat soms gewoon op de pagina zelf.
        let direct = StreampartnerPlayer.firstM3u8(page.html)
        if !direct.isEmpty { return direct }

        try await pages.checkSession(doc)
        if Family7Parser.isAnonymous(doc) {
            throw Family7Error("Deze aflevering is alleen te bekijken als u bent ingelogd met Family7 Plus.")
        }
        throw Family7Error("Kon geen afspeelbare videobron vinden voor deze aflevering.")
    }
}

// MARK: - Live

final class LiveRepository: @unchecked Sendable {
    private let pages = PageFetcher.shared
    private let defaults = UserDefaults.standard
    private static let livePage = "https://www.family7.nl/plus/live"
    private static let lastPlayerKey = "family7.live.lastPlayerURL"
    private static let lastStreamKey = "family7.live.lastStreamURL"

    // MARK: tv-gids

    private let guideSnapshots = SnapshotStore(folder: "family7_guide")
    private let guideLock = NSLock()
    private var guideMemory: [String: (at: Date, items: [GuideItem])] = [:]
    /// De gids verandert zelden; vaker ophalen dan dit is zinloos.
    private static let guideTTL: TimeInterval = 15 * 60

    /// De programmagids van één dag ("yyyy-MM-dd"), zoals de site hem toont.
    /// Binnen een kwartier uit het geheugen, anders opnieuw van de site; lukt
    /// dat niet, dan de laatst bekende versie van schijf.
    func guide(_ date: String, force: Bool = false) async throws -> [GuideItem] {
        if !force, let hit = guideLock.withLock({ guideMemory[date] }), Date().timeIntervalSince(hit.at) < Self.guideTTL {
            return hit.items
        }
        let previous = cachedGuide(date)
        do {
            let page = try await pages.page("https://www.family7.nl/tv-guide-get-items/\(date)T00:00:00/23:59:59/not_today_search", fresh: true)
            guard let json = try JSONSerialization.jsonObject(with: Data(page.html.utf8)) as? [String: Any],
                  let html = json["renderedItems"] as? String else { throw Family7Error("De tv-gids is niet te lezen.") }
            let fresh = Family7Parser.guideItems(try SwiftSoup.parseBodyFragment(html, "https://www.family7.nl"))
            // Eén keer een verdacht lege of halve dag houdt de vorige versie vast.
            let items: [GuideItem]
            if let previous, !PlausibilityGate.shared.accept("guide:\(date)", previous: previous.count, new: fresh.count) {
                items = previous
            } else {
                items = fresh
            }
            guideLock.withLock { guideMemory[date] = (Date(), items) }
            if !items.isEmpty { guideSnapshots.write(items, "guide_\(date)") }
            return items
        } catch {
            if let previous { return previous }
            throw error
        }
    }

    /// Wat er van een dag al bekend is, zonder netwerk: om meteen iets te tonen.
    func cachedGuide(_ date: String) -> [GuideItem]? {
        guideLock.withLock { guideMemory[date]?.items } ?? guideSnapshots.read([GuideItem].self, "guide_\(date)")
    }

    /// Haalt de gids van gisteren tot overmorgen alvast op de achtergrond op,
    /// zodat live meteen de gids toont. Wat al vers is, blijft staan.
    func prefetchGuide() async {
        for offset in -1...2 { _ = try? await guide(Self.guideDate(offset)) }
    }

    /// De datum ("yyyy-MM-dd") in Nederland, `offset` dagen vanaf vandaag.
    static func guideDate(_ offset: Int) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Amsterdam")!
        let day = calendar.date(byAdding: .day, value: offset, to: Date()) ?? Date()
        let format = DateFormatter()
        format.calendar = calendar
        format.timeZone = calendar.timeZone
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "yyyy-MM-dd"
        return format.string(from: day)
    }

    /// Het programma van nu en het stream-adres. De laatst werkende speler- en
    /// stream-adressen worden lokaal onthouden als noodgreep, omdat
    /// Streampartner regelmatig van host wisselt.
    func liveInfo() async throws -> LiveStreamInfo {
        let doc = try await pages.document(Self.livePage, maxAge: 15)
        let html = (try? doc.outerHtml()) ?? ""

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
            imageURL: Family7Parser.imageURL(doc.one(".tv-guide-teaser--image") ?? doc).nonEmpty
                ?? Family7URL.absolute(doc.firstAttribute(".tv-guide-teaser--image img", "src")),
            description: doc.joinedText(".tv-guide-teaser_description, .tv-guide-teaser--info-second p"),
            streamURL: stream
        )
    }

    private func discoverPlayerURL(_ doc: Document, html: String) -> String {
        let player = Family7Parser.playerURL(doc, html: html)
        if !player.isEmpty { return player }
        return Family7URL.absolute(doc.firstAttribute("iframe[src]", "src"))
    }

    private func resolveStream(_ player: String) async -> String {
        guard !player.isEmpty, let page = try? await pages.page(player, referer: "https://www.family7.nl/", fresh: true) else { return "" }
        return StreampartnerPlayer.decodeStreamURLs(page.html).first ?? ""
    }
}

// MARK: - Mijn lijst

/// "Mijn lijst" van het account: dezelfde lijst als op de website.
final class MyListRepository: @unchecked Sendable {
    private let http = Family7HTTP.shared
    private let pages = PageFetcher.shared
    private static let myList = "https://www.family7.nl/plus/mijnlijst"

    func fetch() async throws -> [ProgramItem] {
        do {
            // Altijd vers: net na toevoegen of verwijderen moet de lijst kloppen.
            return Family7Parser.myList(try await pages.document(Self.myList, fresh: true))
        } catch is UnauthorizedError {
            throw Family7Error("Log in om uw lijst te zien.")
        }
    }

    /// Via hetzelfde eindpunt als de knop op de website. Geeft terug of het programma er nu in staat.
    func setInList(nodeId: String, add: Bool) async throws -> Bool {
        guard !nodeId.isEmpty, let url = URL(string: "\(Self.myList)/\(nodeId)/\(add ? "add" : "remove")") else {
            throw Family7Error("Dit programma heeft geen node-id.")
        }
        let (data, response) = try await http.get(url, referer: "https://www.family7.nl/plus",
                                                  headers: ["X-Requested-With": "XMLHttpRequest"], fresh: true)
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
