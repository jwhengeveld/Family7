import SwiftSoup
import XCTest
@testable import Family7

/// Dezelfde gevallen als Family7ParserTest.kt in de Android-apps, op dezelfde
/// echte pagina's van family7.nl (Fixtures/) en bewust verbouwde versies
/// daarvan. Die laatste bewijzen dat de app blijft werken als Family7
/// class-namen hernoemt, afbeeldingen lazy gaat laden of de kaarten anders opbouwt.
final class Family7ParserTests: XCTestCase {

    private func fixture(_ name: String) throws -> String {
        let bundle = Bundle(for: Self.self)
        let parts = name.split(separator: ".")
        let url = try XCTUnwrap(bundle.url(forResource: String(parts[0]), withExtension: String(parts[1])))
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func doc(_ html: String, _ url: String = "https://www.family7.nl/plus") throws -> Document {
        try SwiftSoup.parse(html, url)
    }

    // MARK: A-Z

    func testAZLevertAlleProgrammasMetTitelEnBeeld() throws {
        let programs = Family7Parser.programCards(try doc(fixture("az.html")))
        XCTAssertEqual(programs.count, 196)
        XCTAssertTrue(programs.allSatisfy { !$0.title.isEmpty })
        XCTAssertTrue(programs.allSatisfy { $0.thumbnailURL.hasPrefix("https://www.family7.nl/") })
        XCTAssertEqual(programs.first { $0.slug == "t-weten-waard" }?.title, "'t Weten Waard")
    }

    func testHernoemdeKaartklassen() throws {
        let renamed = try fixture("az.html")
            .replacingOccurrences(of: "view-block_element", with: "tegel")
            .replacingOccurrences(of: "views-row", with: "rij")
            .replacingOccurrences(of: "titleProgramme", with: "naam")
        let programs = Family7Parser.programCards(try doc(renamed))
        XCTAssertEqual(programs.count, 196)
        XCTAssertTrue(programs.allSatisfy { !$0.title.isEmpty })
    }

    func testLazyLoading() throws {
        let lazy = try fixture("az.html").replacingOccurrences(
            of: #"<img\s+src="#, with: #"<img src="data:image/gif;base64,R0lGOD" data-src="#, options: .regularExpression)
        let programs = Family7Parser.programCards(try doc(lazy))
        XCTAssertTrue(programs.allSatisfy { $0.thumbnailURL.hasPrefix("https://www.family7.nl/sites/") })
    }

    func testSrcsetPictureEnAchtergrond() throws {
        let html = """
        <div><a href="/plus/programmas/a"><img srcset="/a-320.jpg 320w, /a-1280.jpg 1280w, /a-640.jpg 640w"></a></div>
        <div><a href="/plus/programmas/b"><picture><source srcset="/b.webp 1x"><img></picture></a></div>
        <div><a href="/plus/programmas/c"><div style="background-image: url('/c.jpg')"></div></a></div>
        """
        let images = Dictionary(uniqueKeysWithValues: Family7Parser.programCards(try doc(html)).map { ($0.slug, $0.thumbnailURL) })
        XCTAssertEqual(images["a"], "https://www.family7.nl/a-1280.jpg")
        XCTAssertEqual(images["b"], "https://www.family7.nl/b.webp")
        XCTAssertEqual(images["c"], "https://www.family7.nl/c.jpg")
    }

    func testLabelIsNooitDeTitel() throws {
        let html = """
        <div class="slider-default_element"><a href="/plus/programmas/fearless"><img src="/f.jpg" title="Fearless"></a>
          <h3 class="ribbon-ribbon">Nieuwe afleveringen</h3></div>
        <div class="slider-default_element"><a href="/plus/programmas/x"><img src="/x.jpg"></a>
          <div class="ribbon"><h3>Nieuw</h3></div><h4>Echte titel</h4></div>
        """
        let programs = Dictionary(uniqueKeysWithValues: Family7Parser.programCards(try doc(html)).map { ($0.slug, $0) })
        XCTAssertEqual(programs["fearless"]?.title, "Fearless")
        XCTAssertEqual(programs["fearless"]?.badge, "Nieuwe afleveringen")
        XCTAssertEqual(programs["x"]?.title, "Echte titel")
    }

    // MARK: startpagina

    func testStartpagina() throws {
        let home = try doc(fixture("startpagina_nagebouwd.html"))
        let hero = try XCTUnwrap(Family7Parser.hero(home))
        XCTAssertEqual(hero.slug, "onvolmaakt-vertrouwen")
        XCTAssertEqual(hero.thumbnailURL, "https://www.family7.nl/sites/default/files/2026-09/OnvolmaaktVertrouwen_header.jpg")

        let rows = Family7Parser.homeRows(home)
        XCTAssertEqual(rows.map(\.id), ["aanbevolen", "kinderprogramma_s"])
        XCTAssertEqual(rows[0].items.map(\.title), ["De Schatkamer", "Eindtijd in Zicht", "Bijbelse karakters"])
        XCTAssertEqual(rows[0].moreURL, "https://www.family7.nl/plus/special/aanbevolen")
    }

    func testStartpaginaZonderBekendeSecties() throws {
        let rebuilt = try fixture("startpagina_nagebouwd.html")
            .replacingOccurrences(of: "on-demand_home-section", with: "rij")
            .replacingOccurrences(of: "slider-default_element", with: "kaart")
            .replacingOccurrences(of: "block-view-header_element-title", with: "kop")
        let rows = Family7Parser.homeRows(try doc(rebuilt))
        XCTAssertTrue(rows.contains { $0.title == "Aanbevolen" && $0.items.count == 3 })
        XCTAssertFalse(rows.contains { $0.title.lowercased() == "mijn lijst" })
    }

    // MARK: programmapagina

    func testProgrammapagina() throws {
        let page = try doc(fixture("programma_1_seizoen.html"), "https://www.family7.nl/plus/programmas/onvolmaakt-vertrouwen")
        let detail = Family7Parser.programDetailBase(page, slug: "onvolmaakt-vertrouwen")
        let episodes = Family7Parser.episodes(page, fallbackThumb: detail.posterURL)
        XCTAssertEqual(detail.title, "Onvolmaakt vertrouwen")
        XCTAssertEqual(detail.nodeId, "201012")
        XCTAssertGreaterThanOrEqual(episodes.count, 4)
        XCTAssertEqual(episodes[0].episodeNumber, "1")
        XCTAssertEqual(episodes[0].title, "Doe niets")
    }

    func testNodeIdUitDrupalSettings() throws {
        let withoutButton = try fixture("programma_1_seizoen.html").replacingOccurrences(of: "data-node-id", with: "data-weg")
        XCTAssertEqual(Family7Parser.nodeId(try doc(withoutButton)), "201012")
    }

    func testSeizoenen() throws {
        let page = try doc(fixture("programma_13_seizoenen.html"))
        let options = Family7Parser.seasonOptions(page)
        XCTAssertEqual(options.count, 13)
        XCTAssertEqual(options.first?.number, "13")
        XCTAssertEqual(Family7Parser.nodeId(page), "84470")
    }

    func testSeizoenEindpunt() throws {
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(try fixture("seizoen_2.json").utf8)) as? [String: Any])
        let html = try XCTUnwrap(json["renderedItems"] as? String)
        let episodes = Family7Parser.episodes(try SwiftSoup.parseBodyFragment(html, "https://www.family7.nl"), fallbackThumb: "")
        XCTAssertEqual(episodes.count, 13)
        XCTAssertEqual(episodes[0].videoSlug, "2-1-bijbelse-karakters")
    }

    func testAfleveringenZonderBekendeKaarten() throws {
        let rebuilt = try fixture("programma_1_seizoen.html")
            .replacingOccurrences(of: "view-block_element", with: "tegel")
            .replacingOccurrences(of: "video-title", with: "kop")
            .replacingOccurrences(of: "video-number", with: "nr")
        let episodes = Family7Parser.episodes(try doc(rebuilt), fallbackThumb: "")
        XCTAssertGreaterThanOrEqual(episodes.count, 4)
        XCTAssertEqual(episodes.first { $0.videoSlug == "1-1-onvolmaakt-vertrouwen" }?.episodeNumber, "1")
    }

    // MARK: spelers en sessie

    func testSpeleradres() throws {
        XCTAssertEqual(Family7Parser.playerURL(try doc(#"<div class="video-player--loader" data-src="https://family7.tv/player.php?itemtoken=x"></div>"#), html: ""),
                       "https://family7.tv/player.php?itemtoken=x")
        let raw = #"<script>var p = "https://family7.tv/player.php?itemtoken=y";</script>"#
        XCTAssertEqual(Family7Parser.playerURL(try doc(raw), html: raw), "https://family7.tv/player.php?itemtoken=y")
    }

    func testInlogEnAnoniemeHerkenning() throws {
        XCTAssertTrue(Family7Parser.isLoginPage(try doc(fixture("inlogpagina.html"))))
        XCTAssertTrue(Family7Parser.isLoginPage(try doc("<p>x</p>"), finalPath: "/user/login"))
        XCTAssertFalse(Family7Parser.isLoginPage(try doc(fixture("az.html"))))
        XCTAssertTrue(Family7Parser.isAnonymous(try doc(fixture("video_publiek.html"))))
        XCTAssertFalse(Family7Parser.isAnonymous(try doc(fixture("startpagina_nagebouwd.html"))))
        XCTAssertTrue(Family7Parser.requiresLogin("https://www.family7.nl/plus"))
        XCTAssertTrue(Family7Parser.requiresLogin("https://www.family7.nl/plus/mijnlijst"))
        XCTAssertFalse(Family7Parser.requiresLogin("https://www.family7.nl/plus/a-z?title=All"))
        XCTAssertFalse(Family7Parser.requiresLogin("https://www.family7.nl/video/1-1-x"))
    }

    // MARK: bron van waarheid

    func testEenBevestigdeVeranderingWint() {
        let gate = PlausibilityGate()
        XCTAssertFalse(gate.accept("az", previous: 196, new: 12))
        XCTAssertTrue(gate.accept("az", previous: 196, new: 12))
    }

    func testEenHaperingDieHerstelt() {
        let gate = PlausibilityGate()
        XCTAssertFalse(gate.accept("home", previous: 196, new: 0))
        XCTAssertTrue(gate.accept("home", previous: 196, new: 197))
        XCTAssertFalse(gate.accept("home", previous: 197, new: 0))
    }

    func testTokenlevensduur() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let end = Int(now.timeIntervalSince1970) + 12 * 3600
        XCTAssertEqual(StreamURLLifetime.validFor("https://x/playlist.m3u8?token_endtime=\(end)&token_hash=a", now: now), 6 * 3600)
        XCTAssertEqual(StreamURLLifetime.validFor("https://x/p.m3u8?token_endtime=\(Int(now.timeIntervalSince1970) + 600)", now: now), 0)
        XCTAssertEqual(StreamURLLifetime.validFor("https://x/index.m3u8?token=abc"), 120)
    }

    func testSeasonCountLabelCountsInsteadOfTrustingAYear() {
        XCTAssertEqual(Family7Parser.seasonCountLabel("2026 seizoenen", seasonCount: 1), "1 seizoen")
        XCTAssertEqual(Family7Parser.seasonCountLabel("2026 seizoenen", seasonCount: 3), "3 seizoenen")
        XCTAssertEqual(Family7Parser.seasonCountLabel("2026 seizoenen", seasonCount: 0), "2026 seizoenen")
        XCTAssertEqual(Family7Parser.seasonCountLabel("Documentaire", seasonCount: 3), "Documentaire")
    }

    func testGuideItemsReadADayFromTheSite() throws {
        let json = try JSONSerialization.jsonObject(with: Data(try fixture("tvgids_dag.json").utf8)) as! [String: Any]
        let doc = try SwiftSoup.parseBodyFragment(json["renderedItems"] as! String, "https://www.family7.nl")
        let items = Family7Parser.guideItems(doc)

        XCTAssertEqual(items.count, 42)
        XCTAssertEqual(items.first?.start, "00:30")
        XCTAssertEqual(items.first?.title, "EuroSpirit")
        XCTAssertEqual(items.map(\.startMinutes), items.map(\.startMinutes).sorted())
        XCTAssertTrue(items.contains { $0.title == "Uitzien… en bouwen!" })
        // Geen Windows-stuurtekens meer in titels of beschrijvingen.
        XCTAssertFalse(items.contains { ($0.title + $0.description + $0.episode).unicodeScalars.contains { (0x80...0x9F).contains($0.value) } })

        let joyce = try XCTUnwrap(items.first { $0.title == "Joyce Meyer" })
        XCTAssertEqual(joyce.start, "01:00")
        XCTAssertEqual(joyce.episode, "Aflevering 194 - Wat doe je als het leven pijn doet?")
        XCTAssertEqual(joyce.programSlug, "joyce-meyer")
        XCTAssertEqual(joyce.videoSlug, "2026-194-joyce-meyer")
        XCTAssertTrue(joyce.imageURL.hasPrefix("https://www.family7.nl/sites/default/files/"))
        XCTAssertFalse(items.contains { $0.imageURL.contains("fam7logo") })
    }

    func testSuggestionsPutTitlesStartingWithTheQueryFirst() {
        let all = ["Into the Amazone", "De Diamant", "Diamant en Goud", "Het Spoor"].map {
            ProgramItem(id: $0, slug: $0.lowercased().replacingOccurrences(of: " ", with: "-"), title: $0, thumbnailURL: "")
        }
        XCTAssertEqual(ProgramItem.suggestions(all, for: "diam").map(\.title), ["Diamant en Goud", "De Diamant"])
        XCTAssertEqual(ProgramItem.suggestions(all, for: "  ").count, 0)
    }
}
