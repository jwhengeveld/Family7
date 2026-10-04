import Foundation

/// Leest het stream-adres uit de speler die Family7 op zijn pagina's zet.
///
/// De speler van Streampartner levert dat adres niet als platte tekst, maar
/// ingepakt in een `eval(function(w,i,s,e){...})`-constructie. Deze code doet
/// precies wat de browser ook doet: de tekst uitpakken en de .m3u8 eruit halen.
/// Een letterlijke port van StreampartnerPlayer.kt uit de Android-apps.
enum StreampartnerPlayer {

    /// Hoe vaak een speler zijn eigen uitvoer nog eens mag inpakken.
    private static let maxDepth = 6

    private static let m3u8 = try! NSRegularExpression(pattern: #"https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"#)
    private static let mp4 = try! NSRegularExpression(pattern: #"https?://[^\s"'<>]+\.mp4[^\s"'<>]*"#)
    private static let evalCall = try! NSRegularExpression(
        pattern: #"eval\(function\(w,i,s,e\)\{.*?\}\((.*?)\)\)"#,
        options: [.dotMatchesLineSeparators]
    )
    private static let jsString = try! NSRegularExpression(pattern: #"'(.*?)'"#)
    private static let labelledSource = try! NSRegularExpression(
        pattern: #"src:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"#
    )

    /// Het eerste stream-adres dat letterlijk in de tekst staat, of leeg.
    static func firstM3u8(_ text: String) -> String {
        matches(m3u8, in: text).first ?? ""
    }

    /// Alle stream-adressen, ook die pas na een of meer keer uitpakken
    /// zichtbaar worden. Wat er direct in staat komt eerst.
    static func decodeStreamURLs(_ text: String, depth: Int = 0) -> [String] {
        guard depth <= maxDepth else { return [] }

        var found = matches(m3u8, in: text)
        for arguments in groups(evalCall, in: text) {
            let parsed = parseJsStringArgs(arguments)
            guard !parsed.isEmpty else { continue }
            found.append(contentsOf: decodeStreamURLs(unpack(parsed), depth: depth + 1))
        }

        var seen = Set<String>()
        return found.filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    /// Het stream-adres uit een spelerpagina: eerst wat er als "src:" staat,
    /// dan wat er letterlijk staat, dan wat er ingepakt staat, en als laatste
    /// een los mp4-bestand (oudere afleveringen).
    static func streamURL(fromPlayerHTML html: String) -> String {
        if let labelled = groups(labelledSource, in: html).first { return labelled }
        let direct = firstM3u8(html)
        if !direct.isEmpty { return direct }
        if let decoded = decodeStreamURLs(html).first { return decoded }
        return matches(mp4, in: html).first ?? ""
    }

    /// De tekenreeksen waarmee de speler wordt aangeroepen.
    static func parseJsStringArgs(_ raw: String) -> [String] {
        groups(jsString, in: raw)
    }

    private static func unpack(_ arguments: [String]) -> String {
        let w = arguments.count > 0 ? arguments[0] : ""
        let i = arguments.count > 1 ? arguments[1] : ""
        let s = arguments.count > 2 ? arguments[2] : ""
        let e = arguments.count > 3 ? arguments[3] : ""
        // De eenvoudige variant: vier argumenten, alleen het eerste gevuld, en
        // dat is dan kale base36.
        if arguments.count == 4 && i.isEmpty && s.isEmpty && e.isEmpty {
            return unpackBase36(w)
        }
        return unpackInterleaved(w, i, s)
    }

    /// Paren van twee tekens, elk paar een teken in grondtal 36.
    static func unpackBase36(_ packed: String) -> String {
        let characters = Array(packed)
        var out = ""
        var index = 0
        while index < characters.count {
            let pair = String(characters[index..<min(index + 2, characters.count)])
            // Een onvolledig of ongeldig paar slaan we over; de rest blijft bruikbaar.
            if let code = Int(pair, radix: 36), let scalar = UnicodeScalar(code) {
                out.unicodeScalars.append(scalar)
            }
            index += 2
        }
        return out
    }

    /// De variant met meerdere tekenreeksen: de eerste vijf tekens van elk
    /// argument vormen de sleutel, de rest de ingepakte tekst. De pariteit van
    /// het sleutelteken bepaalt per teken of er een bij of af gaat.
    static func unpackInterleaved(_ w: String, _ i: String, _ s: String) -> String {
        let sources = [Array(w), Array(i), Array(s)]
        let longest = sources.map(\.count).max() ?? 0
        var payload: [Character] = []
        var key: [Character] = []
        for position in 0..<longest {
            for source in sources where position < source.count {
                if position < 5 { key.append(source[position]) } else { payload.append(source[position]) }
            }
        }
        guard !key.isEmpty else { return "" }

        var out = ""
        var keyIndex = 0
        var index = 0
        while index < payload.count {
            let keyCode = Int(key[keyIndex].unicodeScalars.first!.value)
            let shift = keyCode % 2 != 0 ? 1 : -1
            let pair = String(payload[index..<min(index + 2, payload.count)])
            if let code = Int(pair, radix: 36), let scalar = UnicodeScalar(code - shift) {
                out.unicodeScalars.append(scalar)
            }
            keyIndex = (keyIndex + 1) % key.count
            index += 2
        }
        return out
    }

    // MARK: - regex-hulpjes

    private static func matches(_ regex: NSRegularExpression, in text: String) -> [String] {
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap { Range($0.range, in: text).map { String(text[$0]) } }
    }

    private static func groups(_ regex: NSRegularExpression, in text: String) -> [String] {
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            Range(match.range(at: 1), in: text).map { String(text[$0]) }
        }
    }
}
