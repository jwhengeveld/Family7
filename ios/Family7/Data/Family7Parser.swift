import Foundation
import SwiftSoup

/// Leest de pagina's van family7.nl. Port van Family7Parser.kt in de
/// Android-apps; los van het netwerk, zodat het gedrag met echte (en bewust
/// verbouwde) pagina's te testen is.
///
/// Bestand tegen veranderingen aan de site: elk gegeven heeft een vaste route
/// via de huidige opmaak, en daarachter vangnetten die niet op class-namen
/// leunen: links naar /programmas/ en /video/, afbeeldingen in welke vorm ook,
/// de paginatitel en de node-id die Drupal zelf in drupalSettings zet.
enum Family7Parser {

    static let baseURL = "https://www.family7.nl"

    private static let cardSelector = [
        ".slider-default_element", ".more-series-on-demand_element",
        ".view-block_element-wrapper", ".view-block_element", ".views-row"
    ].joined(separator: ", ")
    private static let programLink = "a[href*='/programmas/']"
    private static let videoLink = "a[href*='/video/']"
    private static let suppressedRowTitles: Set<String> = ["mijn lijst", "mijn lijstje"]
    static let kidsHints = ["kinder", "kids", "jeugd"]

    // MARK: - sessie

    /// Of dit de inlogpagina is in plaats van de gevraagde pagina.
    static func isLoginPage(_ doc: Document, finalPath: String = "") -> Bool {
        finalPath.trimmingCharacters(in: CharacterSet(charactersIn: "/")).hasSuffix("user/login")
            || doc.one("input[name=form_id][value=user_login_form], form#user-login-form") != nil
    }

    /// Het menu dat Drupal alleen aan anonieme bezoekers toont.
    static func isAnonymous(_ doc: Document) -> Bool {
        doc.one(".menu-anonymous-user, .account-menu_menu-anonymous-user") != nil
    }

    /// Pagina's die alleen ingelogd hun inhoud tonen; programma-, video- en
    /// A-Z-pagina's zijn ook anoniem te lezen.
    static func requiresLogin(_ url: String) -> Bool {
        let path = (url.components(separatedBy: "family7.nl").last ?? url)
            .components(separatedBy: "?").first?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) ?? ""
        return path == "plus" || ["plus/mijnlijst", "plus/live", "plus/nieuw", "ondemandkijken", "plus/special"]
            .contains { path.hasPrefix($0) }
    }

    // MARK: - algemeen

    /// Het beeld bij een element, in welke vorm de site het ook aanlevert: src,
    /// lazy loading (data-src en varianten), srcset (het grootste), picture, of
    /// een achtergrondafbeelding in een stijlregel.
    static func imageURL(_ scope: Element) -> String {
        let img = scope.tagName() == "img" ? scope : scope.one("img")
        if let img {
            for attr in ["data-src", "data-lazy-src", "data-original", "src"] {
                let value = img.attribute(attr)
                if !value.isEmpty && !value.hasPrefix("data:") { return Family7URL.absolute(value) }
            }
            for attr in ["data-srcset", "srcset"] {
                if let best = largest(fromSrcset: img.attribute(attr)) { return Family7URL.absolute(best) }
            }
        }
        if let source = scope.one("picture source[srcset], source[data-srcset]") {
            let set = source.attribute("srcset").nonEmpty ?? source.attribute("data-srcset")
            if let best = largest(fromSrcset: set) { return Family7URL.absolute(best) }
        }
        for element in [scope] + scope.all("[style*=url]") {
            if let url = styleImage(element.attribute("style")) { return Family7URL.absolute(url) }
        }
        return ""
    }

    private static func largest(fromSrcset srcset: String) -> String? {
        srcset.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .max { width($0) < width($1) }
            .flatMap { $0.split(separator: " ").first.map(String.init) }
            .flatMap { $0.isEmpty || $0.hasPrefix("data:") ? nil : $0 }
    }

    private static func width(_ part: String) -> Int {
        Int((part.split(separator: " ").last.map(String.init) ?? "").filter(\.isNumber)) ?? 0
    }

    static func styleImage(_ style: String) -> String? {
        guard let range = style.range(of: #"url\(\s*['"]?([^'")]+)['"]?\s*\)"#, options: .regularExpression) else { return nil }
        return String(style[range])
            .replacingOccurrences(of: #"^url\(\s*['"]?"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"['"]?\s*\)$"#, with: "", options: .regularExpression)
    }

    /// Het Drupal-node-id: eerst de knop "Mijn lijst", dan drupalSettings.
    static func nodeId(_ doc: Document) -> String {
        if let id = doc.one(".process-to-my-series-list[data-node-id], .tabs-more[data-node-id], [data-node-id]")?
            .attribute("data-node-id").nonEmpty { return id }
        guard let script = doc.one("script[data-drupal-selector=drupal-settings-json]"),
              let data = script.data().data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let id = json["nodeId"] as? String, !id.isEmpty { return id }
        if let id = json["nodeId"] as? Int { return String(id) }
        let current = (json["path"] as? [String: Any])?["currentPath"] as? String ?? ""
        guard let range = current.range(of: "node/") else { return "" }
        return String(current[range.upperBound...].prefix(while: \.isNumber))
    }

    /// og:title, de kop, of de paginatitel zonder " | Family7".
    static func pageTitle(_ doc: Document) -> String {
        doc.one("meta[property=og:title]")?.attribute("content").nonEmpty
            ?? doc.one("h1")?.plainText.nonEmpty
            ?? ((try? doc.title()) ?? "").components(separatedBy: "|").first?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    // MARK: - programma's

    /// Alle programmakaarten: eerst de bekende kaarten, anders elke link naar een programma.
    static func programCards(_ scope: Element) -> [ProgramItem] {
        var found = scope.all(cardSelector).compactMap(programFromCard)
        if found.isEmpty {
            found = scope.all(programLink).compactMap { programFromCard(cardAround($0)) }
        }
        var seen = Set<String>()
        return found.filter { seen.insert($0.slug).inserted }
    }

    /// De kaart rond een link, zonder dat hij meerdere programma's omvat.
    private static func cardAround(_ link: Element) -> Element {
        var card = link
        for _ in 0..<2 {
            guard let parent = card.parent() else { return card }
            let slugs = Set(parent.all(programLink).map { Family7URL.slug($0.attribute("href")) })
            if slugs.count > 1 { return card }
            card = parent
        }
        return card
    }

    private static func programFromCard(_ card: Element) -> ProgramItem? {
        let isLink = card.tagName() == "a" && card.attribute("href").contains("/programmas/")
        guard let link = isLink ? card : card.one(programLink) else { return nil }
        let href = link.attribute("href")
        guard !href.isEmpty, !href.hasPrefix("#") else { return nil }
        let slug = Family7URL.slug(href)
        guard !slug.isEmpty, slug != "programmas" else { return nil }
        let img = card.one("img")
        let linkText = link.plainText
        let badge = card.one("[class*=ribbon], .badge, .label")?.plainText ?? ""
        // Een label als "Nieuwe afleveringen" is nooit de titel, ook niet in een kop.
        func usable(_ text: String?) -> String? {
            guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty,
                  text.caseInsensitiveCompare(badge) != .orderedSame else { return nil }
            return text
        }
        func isRibbon(_ el: Element) -> Bool { ((try? el.className()) ?? "").contains("ribbon") }
        func heading(_ query: String) -> String? {
            card.all(query)
                .filter { el in !isRibbon(el) && !el.parents().array().contains(where: isRibbon) }
                .compactMap { usable($0.plainText) }
                .first
        }
        // Volgorde: vaste titelklassen; dan het title-attribuut van de afbeelding
        // (op de sliders staat de naam alleen daar); dan algemene koppen.
        let title = heading(".titleProgramme, .view-block_element-title")
            ?? usable(img?.attribute("title"))
            ?? heading(".title, h3, h4")
            ?? usable(link.attribute("title"))
            ?? (linkText.count < 80 ? usable(linkText) : nil)
            ?? Family7URL.titleFromSlug(slug)
        return ProgramItem(
            id: href, slug: slug, title: title,
            thumbnailURL: imageURL(card),
            badge: badge,
            url: Family7URL.absolute(href),
            nodeId: card.one("[data-node-id]")?.attribute("data-node-id") ?? ""
        )
    }

    // MARK: - startpagina

    static func hero(_ doc: Document) -> ProgramItem? {
        guard let header = doc.one("section.on-demand-header, .on-demand-header, .hero, [class*=hero]"),
              let href = header.one(programLink)?.attribute("href"), !href.isEmpty else { return nil }
        let style = header.all("style").map { $0.data() }.joined(separator: " ")
        let slug = Family7URL.slug(href)
        return ProgramItem(
            id: href, slug: slug,
            title: header.one("h1, h2, .title")?.plainText.nonEmpty ?? Family7URL.titleFromSlug(slug),
            thumbnailURL: Family7URL.absolute(styleImage(style) ?? imageURL(header)),
            url: Family7URL.absolute(href),
            description: header.one(".introduction, p")?.plainText ?? "",
            nodeId: header.one("[data-node-id]")?.attribute("data-node-id") ?? ""
        )
    }

    static func homeRows(_ doc: Document) -> [CategoryRow] {
        var blocks = doc.all("section.on-demand_home-section, .block-view")
        if blocks.isEmpty { blocks = doc.all("section, .block").filter { $0.one("h2, h3") != nil } }
        var rows: [CategoryRow] = []
        for block in blocks {
            let title = block.one(".block-view-header_element-title, h3, h2")?.plainText ?? ""
            if title.isEmpty || suppressedRowTitles.contains(title.lowercased()) { continue }
            let items = programCards(block)
            if items.isEmpty { continue }
            let id = slugifyTitle(title)
            if rows.contains(where: { $0.id == id }) { continue }
            let more = Family7URL.absolute(block.one(".more-link a[href], a[href*='/plus/special/'], a.more[href]")?.attribute("href") ?? "")
            rows.append(CategoryRow(id: id, title: title, moreURL: more, items: items))
        }
        return rows
    }

    static func slugifyTitle(_ title: String) -> String {
        title.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
    }

    static func hasNextPage(_ doc: Document) -> Bool {
        !doc.all(".pager__item--next a, li.pager-next a, a[rel=next]").isEmpty
    }

    static func lastPageNumber(_ doc: Document) -> Int? {
        let href = doc.firstAttribute(".pager__item--last a[href], li.pager-last a[href]", "href")
        guard let range = href.range(of: #"[?&]page=(\d+)"#, options: .regularExpression) else { return nil }
        return Int(href[range].filter(\.isNumber))
    }

    // MARK: - programmapagina

    struct SeasonOption: Equatable {
        let number: String
        let title: String
        let selected: Bool
    }

    /// De site zet boven een programma "N seizoenen", maar bij sommige series
    /// staat daar het hoogste seizoensnummer (een jaartal: "2026 seizoenen").
    /// Kennen we de seizoenen, dan tellen we zelf; andere tekst blijft staan.
    static func seasonCountLabel(_ category: String, seasonCount: Int) -> String {
        let text = category.trimmingCharacters(in: .whitespacesAndNewlines)
        guard seasonCount > 0,
              text.range(of: #"^\d+\s+seizoen(en)?$"#, options: [.regularExpression, .caseInsensitive]) != nil
        else { return category }
        return seasonCount == 1 ? "1 seizoen" : "\(seasonCount) seizoenen"
    }

    static func seasonOptions(_ doc: Document) -> [SeasonOption] {
        guard let select = doc.one(".more-videos_season-select, select[class*=season], select[name*=season]") else { return [] }
        return select.all("option").compactMap { option in
            let value = option.attribute("value")
            guard !value.isEmpty else { return nil }
            return SeasonOption(number: value, title: option.plainText.nonEmpty ?? "Seizoen \(value)",
                                selected: option.hasAttr("selected"))
        }
    }

    static func programDetailBase(_ doc: Document, slug: String) -> ProgramDetail {
        let poster = doc.one(".video-page-top-content img, .series-page-image img, .main-image img").map(imageURL)?.nonEmpty
            ?? doc.one("meta[property=og:image]")?.attribute("content") ?? ""
        let button = doc.one(".process-to-my-series-list, [data-node-id]")
        return ProgramDetail(
            slug: slug,
            title: pageTitle(doc).nonEmpty ?? Family7URL.titleFromSlug(slug),
            posterURL: Family7URL.absolute(poster),
            description: doc.one(".introduction, .series-page-description, .field--name-body")?.plainText
                ?? doc.one("meta[name=description], meta[property=og:description]")?.attribute("content") ?? "",
            category: doc.one(".series-info")?.plainText ?? "",
            nodeId: nodeId(doc),
            isInMyList: button?.hasClass("added") ?? false
        )
    }

    /// De afleveringen in een stuk pagina; vangnet: elke link naar een video.
    static func episodes(_ scope: Element, fallbackThumb: String) -> [EpisodeItem] {
        var found = scope.all(".view-block_element-wrapper, .view-block_element").compactMap { episode(from: $0, fallbackThumb: fallbackThumb) }
        if found.isEmpty {
            found = scope.all(videoLink).compactMap { episode(from: $0.parent() ?? $0, fallbackThumb: fallbackThumb) }
        }
        var seen = Set<String>()
        return found.filter { seen.insert($0.videoSlug).inserted }
    }

    private static func episode(from card: Element, fallbackThumb: String) -> EpisodeItem? {
        let isLink = card.tagName() == "a" && card.attribute("href").contains("/video/")
        guard let link = isLink ? card : card.one(videoLink) else { return nil }
        let href = link.attribute("href")
        let slug = Family7URL.slug(href)
        guard !slug.isEmpty else { return nil }
        let titleBlock = card.one(".video-title")
        let alt = card.one("img")?.attribute("alt") ?? ""
        let bareSlug = slug.replacingOccurrences(of: #"^\d+-\d+-"#, with: "", options: .regularExpression)
        let title = titleBlock?.one(".float-left")?.plainText.nonEmpty
            ?? titleBlock?.ownText().trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            ?? card.one(".view-block_element-title, .title, h3, h4")?.plainText.nonEmpty
            ?? link.attribute("title").nonEmpty
            ?? (alt.contains("_") ? nil : alt.nonEmpty)
            ?? Family7URL.titleFromSlug(bareSlug)
        let parts = slug.split(separator: "-")
        let numberFromSlug = parts.count > 1 && parts[1].allSatisfy(\.isNumber) ? String(parts[1]) : ""
        return EpisodeItem(
            id: slug,
            episodeNumber: card.one(".video-number")?.plainText.nonEmpty ?? numberFromSlug,
            title: title,
            description: card.one(".video-description")?.plainText ?? "",
            duration: titleBlock?.one(".float-right")?.plainText ?? "",
            thumbnailURL: imageURL(card).nonEmpty ?? fallbackThumb,
            videoSlug: slug,
            videoURL: Family7URL.absolute(href)
        )
    }

    // MARK: - spelers

    static func playerURL(_ doc: Document, html: String) -> String {
        let candidates = [
            doc.firstAttribute(".video-player--loader, .video-player--frame", "data-src"),
            doc.firstAttribute("iframe[src*='player.php']", "src"),
            doc.firstAttribute("[data-src*='player'], [data-src*='streampartner']", "data-src"),
            doc.firstAttribute("iframe[src*='player'], iframe[src*='streampartner']", "src")
        ]
        if let found = candidates.first(where: { !$0.isEmpty }) { return Family7URL.absolute(found) }
        if let range = html.range(of: #"https?://[^\s"'<>]*(?:player\.php|streampartner\.nl/)[^\s"'<>]*"#, options: .regularExpression) {
            return String(html[range])
        }
        return ""
    }

    static func myList(_ doc: Document) -> [ProgramItem] {
        programCards(doc.body() ?? doc)
    }
}

// MARK: - plausibiliteit en tokens

/// Of een nieuwe uitkomst een goede vorige mag vervangen. Een site-verbouwing
/// levert vaak een pagina op die wel laadt maar (bijna) niets oplevert; dan
/// blijft de laatst goede versie staan.
enum Plausibility {
    static func acceptable(previous: Int?, new: Int) -> Bool {
        guard let previous, previous > 0 else { return true }
        if new == 0 { return false }
        if previous < 10 { return true }
        return Double(new) >= Double(previous) * 0.4
    }
}

/// Hoe lang een stream-adres bruikbaar is: tot ruim voor het einde van het
/// Wowza-token in het adres, en zonder herkenbaar token twee minuten.
enum StreamURLLifetime {
    static func validFor(_ url: String, now: Date = Date()) -> TimeInterval {
        guard let range = url.range(of: #"(?i)[?&][a-z_]*endtime=(\d{9,13})"#, options: .regularExpression),
              let value = Double(url[range].split(separator: "=").last ?? "") else { return 120 }
        let end = value < 100_000_000_000 ? value : value / 1000
        let remaining = end - now.timeIntervalSince1970 - 45 * 60
        return remaining <= 0 ? 0 : min(remaining, 6 * 3600)
    }
}

extension Family7Parser {
    // MARK: - tv-gids

    /// De uitzendingen uit de tv-gids van de site (/tv-guide-get-items, het
    /// "renderedItems"-deel). Items zonder tijd of titel slaan we over; het
    /// standaard-Family7-logo is geen programmabeeld.
    static func guideItems(_ doc: Element) -> [GuideItem] {
        var seen = Set<String>()
        return guideEntries(doc).compactMap { item -> GuideItem? in
            let timeSource = item.one(".tv-guide-item-time, [class*=time], time")?.plainText ?? item.plainText
            guard let range = timeSource.range(of: guideTime, options: .regularExpression) else { return nil }
            var time = String(timeSource[range])
            if time.count == 4 { time = "0" + time }
            let title = cleanGuideText(item.one(".tv-guide-item-title, [class*=title], h2, h3, h4, strong")?.plainText ?? "")
            // Een "titel" die alleen de tijd is, is geen titel.
            guard !title.isEmpty, title.range(of: "^" + guideTime + "$", options: .regularExpression) == nil else { return nil }
            let images = (try? item.select("img").array()) ?? []
            let picked = item.one(".tv-guide-item-image img, [class*=image] img")
                ?? images.first { img in !isGuideIcon(((try? img.attr("src")) ?? "") + ((try? img.attr("alt")) ?? "")) }
            let image = picked.map { img in
                let lazy = (try? img.attr("data-src")) ?? ""
                return lazy.isEmpty ? ((try? img.attr("src")) ?? "") : lazy
            } ?? ""
            let links = ((try? item.select("a[href]").array()) ?? []).compactMap { try? $0.attr("href") }
            func slug(_ pattern: String) -> String {
                for link in links {
                    if let match = link.range(of: pattern, options: .regularExpression) {
                        return String(link[match]).components(separatedBy: "/").last ?? ""
                    }
                }
                return ""
            }
            // Dubbel genoemde uitzendingen (een omhulsel en zijn inhoud) één keer.
            guard seen.insert(time + "|" + title).inserted else { return nil }
            return GuideItem(
                start: time,
                title: title,
                episode: cleanGuideText(item.one(".tv-guide-item-data, [class*=data], [class*=subtitle], [class*=episode]")?.plainText ?? ""),
                description: cleanGuideText(item.one(".tv-guide-item-description, [class*=description], [class*=desc]")?.plainText ?? ""),
                imageURL: image.isEmpty || isGuideIcon(image) ? "" : Family7URL.absolute(image),
                programSlug: slug(#"/programmas/[^/?#]+"#),
                videoSlug: slug(#"/video/[^/?#]+"#)
            )
        }
    }

    private static let guideTime = #"\b\d{1,2}:\d{2}\b"#
    private static let guideHeading = "h2, h3, h4, strong, [class*=title]"

    /// Kijkwijzer-icoontjes en het Family7-logo zijn geen programmabeeld.
    private static func isGuideIcon(_ text: String) -> Bool {
        text.range(of: #"(?i)kijkwijzer|fam7logo|/icon/|logo\.(png|jpe?g|svg)"#, options: .regularExpression) != nil
    }

    /// De uitzendingen in de gids. Eerst de bekende opmaak van de site; is die
    /// verbouwd, dan elk lijstitem of artikel met een tijd en een kop, zonder
    /// zulke items erin (het binnenste item, niet de hele lijst).
    private static func guideEntries(_ doc: Element) -> [Element] {
        let known = (try? doc.select("li.tv-guide-item").array()) ?? []
        if !known.isEmpty { return known }
        func isEntry(_ el: Element) -> Bool {
            el.plainText.range(of: guideTime, options: .regularExpression) != nil && el.one(guideHeading) != nil
        }
        let candidates = (try? doc.select("li, article, [class*=item]").array()) ?? []
        return candidates.filter { el in
            isEntry(el) && !(((try? el.select("li, article").array()) ?? []).contains { $0 !== el && isEntry($0) })
        }
    }

    /// De gids bevat Windows-tekens die als stuurcode binnenkomen (U+0080 tot
    /// U+009F: aanhalingstekens, beletselteken); die worden de echte tekens.
    static func cleanGuideText(_ text: String) -> String {
        let fixed = String(String.UnicodeScalarView(text.unicodeScalars.map { scalar -> Unicode.Scalar in
            guard (0x80...0x9F).contains(scalar.value),
                  let mapped = String(data: Data([UInt8(scalar.value)]), encoding: .windowsCP1252)?.unicodeScalars.first,
                  !(0x80...0x9F).contains(mapped.value) else {
                return (0x80...0x9F).contains(scalar.value) ? " " : scalar
            }
            return mapped
        }))
        return fixed.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    }

}
