import XCTest
@testable import Family7

/// Dezelfde gevallen als StreampartnerPlayerTest.kt in de Android-apps: een
/// adres eerst zelf inpakken en controleren dat de app er weer hetzelfde uit haalt.
final class StreampartnerPlayerTests: XCTestCase {

    private let streamURL = "https://highvolume08.streampartner.nl/family7/index.m3u8?token=abc123"

    // MARK: inpakhulpjes, de omgekeerde weg van de app

    private func packBase36(_ plain: String) -> String {
        plain.unicodeScalars.map { scalar in
            let packed = String(scalar.value, radix: 36)
            return packed.count < 2 ? "0" + packed : packed
        }.joined()
    }

    private func packInterleaved(_ plain: String, key: String) -> (String, String, String) {
        let keyChars = Array(key)
        precondition(keyChars.count == 15)
        var payload = ""
        var keyIndex = 0
        for scalar in plain.unicodeScalars {
            let shift = Int(keyChars[keyIndex].unicodeScalars.first!.value) % 2 != 0 ? 1 : -1
            let packed = String(Int(scalar.value) + shift, radix: 36)
            payload += packed.count < 2 ? "0" + packed : packed
            keyIndex = (keyIndex + 1) % keyChars.count
        }
        var w = "", i = "", s = ""
        for position in 0..<5 {
            w.append(keyChars[position * 3])
            i.append(keyChars[position * 3 + 1])
            s.append(keyChars[position * 3 + 2])
        }
        return (w + payload, i, s)
    }

    private func evalCall(_ arguments: String...) -> String {
        "eval(function(w,i,s,e){var x=1;}(" + arguments.map { "'\($0)'" }.joined(separator: ",") + "))"
    }

    // MARK: tests

    func testParenInGrondtal36WordenWeerTekst() {
        XCTAssertEqual(StreampartnerPlayer.unpackBase36("1b1b0d0a"), "//\r\n")
    }

    func testIngepaktAdresKomtErHeelWeerUit() {
        XCTAssertEqual(StreampartnerPlayer.unpackBase36(packBase36(streamURL)), streamURL)
    }

    func testOnleesbaarPaarGooitDeRestNietWeg() {
        XCTAssertEqual(StreampartnerPlayer.unpackBase36(packBase36("abc") + "!!"), "abc")
    }

    func testVariantMetSleutelKomtErHeelWeerUit() {
        let (w, i, s) = packInterleaved(streamURL, key: "K3y-v00r-Test!1")
        XCTAssertEqual(StreampartnerPlayer.unpackInterleaved(w, i, s), streamURL)
    }

    func testZonderSleutelGeenUitkomstInPlaatsVanCrash() {
        XCTAssertEqual(StreampartnerPlayer.unpackInterleaved("", "", ""), "")
    }

    func testAdresDatGewoonInDeTekstStaat() {
        let html = #"<video><source src="\#(streamURL)" type="application/x-mpegURL"></video>"#
        XCTAssertEqual(StreampartnerPlayer.firstM3u8(html), streamURL)
    }

    func testGeenAdresGeeftLeeg() {
        XCTAssertEqual(StreampartnerPlayer.firstM3u8("<html><body>niets</body></html>"), "")
    }

    func testAdresUitEvalMetAlleenEersteArgument() {
        let html = "<script>" + evalCall(packBase36(streamURL), "", "", "") + "</script>"
        XCTAssertEqual(StreampartnerPlayer.decodeStreamURLs(html), [streamURL])
    }

    func testAdresUitEvalMetSleutel() {
        let (w, i, s) = packInterleaved(streamURL, key: "abcde12345fghij")
        let html = "<script>" + evalCall(w, i, s, "") + "</script>"
        XCTAssertEqual(StreampartnerPlayer.decodeStreamURLs(html), [streamURL])
    }

    func testEvalBinnenEval() {
        let inner = "<script>" + evalCall(packBase36(streamURL), "", "", "") + "</script>"
        let outer = "<script>" + evalCall(packBase36(inner), "", "", "") + "</script>"
        XCTAssertEqual(StreampartnerPlayer.decodeStreamURLs(outer), [streamURL])
    }

    func testEindeloosIngepaktStoptNetjes() {
        var html = "<script>" + evalCall(packBase36("leeg"), "", "", "") + "</script>"
        for _ in 0..<8 { html = "<script>" + evalCall(packBase36(html), "", "", "") + "</script>" }
        XCTAssertTrue(StreampartnerPlayer.decodeStreamURLs(html).isEmpty)
    }

    func testArgumentenUitDeAanroep() {
        XCTAssertEqual(StreampartnerPlayer.parseJsStringArgs("'een','','twee',''"), ["een", "", "twee", ""])
    }

    func testHetzelfdeAdresNietDubbel() {
        let html = #"<a href="\#(streamURL)">een</a><a href="\#(streamURL)">twee</a>"#
        XCTAssertEqual(StreampartnerPlayer.decodeStreamURLs(html), [streamURL])
    }

    func testSpelerpaginaMetSrcLabelGaatVoor() {
        let html = #"var a="https://x.nl/oud.m3u8"; player({src: "\#(streamURL)"})"#
        XCTAssertEqual(StreampartnerPlayer.streamURL(fromPlayerHTML: html), streamURL)
    }
}
