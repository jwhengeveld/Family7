import Foundation

// Dezelfde modellen als de datalaag van de Android-apps (core/), zodat het
// gedrag van beide platforms naast elkaar te leggen is.

struct LiveStreamInfo: Hashable, Sendable {
    var title = "Family7 Live"
    var currentProgram = "Family7 Uitzending"
    var timeRange = ""
    var imageURL = ""
    var description = ""
    var streamURL = ""
}

struct ProgramItem: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var slug: String
    var title: String
    var thumbnailURL: String
    var badge = ""
    var url = ""
    var description = ""
    /// Drupal node-id, nodig om het programma aan "Mijn lijst" toe te voegen.
    var nodeId = ""
}

struct CategoryRow: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var title: String
    var moreURL = ""
    var items: [ProgramItem] = []
}

struct EpisodeItem: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var episodeNumber: String
    var title: String
    var description = ""
    var duration = ""
    var thumbnailURL = ""
    var videoSlug: String
    var videoURL: String

    /// "Afl. 3: Titel", of alleen de titel als de aflevering geen nummer heeft.
    var displayLabel: String {
        episodeNumber.isEmpty ? title : "Afl. \(episodeNumber): \(title)"
    }

    /// Family7 zet bij sommige series "00 m"; een nul-duur zegt niets.
    var meaningfulDuration: String? {
        duration.contains(where: { ("1"..."9").contains($0) }) ? duration : nil
    }
}

struct SeasonInfo: Codable, Hashable, Sendable {
    var seasonNumber: String
    var title: String
    var episodes: [EpisodeItem]
}

struct ProgramDetail: Codable, Hashable, Sendable {
    var slug: String
    var title: String
    var posterURL: String
    var description: String
    var category = ""
    var seasons: [SeasonInfo] = []
    /// Drupal node-id van het programma, gelezen uit de "Mijn lijst"-knop.
    var nodeId = ""
    /// Of Family7 dit programma al in de lijst van dit account heeft staan.
    var isInMyList = false
}

/// Een fout met een tekst die een kijker begrijpt.
struct Family7Error: LocalizedError, Sendable {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Eén uitzending in de programmagids van de site. `start` is "HH:mm" in
/// Nederlandse tijd; de dag staat bij de lijst waar het item in zit.
struct GuideItem: Codable, Hashable, Sendable {
    var start: String
    var title: String
    var episode = ""
    var description = ""
    var imageURL = ""
    /// Het programma op de site, als de gids ernaar linkt.
    var programSlug = ""
    /// De aflevering die al terug te kijken is, als de gids ernaar linkt.
    var videoSlug = ""

    /// Minuten na middernacht, om het "nu" te bepalen.
    var startMinutes: Int {
        let parts = start.split(separator: ":").compactMap { Int($0) }
        return (parts.first ?? 0) * 60 + (parts.count > 1 ? parts[1] : 0)
    }
}

