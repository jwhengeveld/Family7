import XCTest
@testable import Family7

final class CachingTests: XCTestCase {

    func testVerseWaardeKomtTerug() {
        let cache = TimedCache<String>(ttl: 60)
        cache.put("catalogus")
        XCTAssertEqual(cache.fresh, "catalogus")
        XCTAssertEqual(cache.snapshot, "catalogus")
    }

    func testVerlopenWaardeIsNietVersMaarWelBruikbaar() {
        let cache = TimedCache<String>(ttl: 0)
        cache.put("catalogus")
        XCTAssertNil(cache.fresh)
        XCTAssertEqual(cache.snapshot, "catalogus")
    }

    func testWaardeVanSchijfTeltNietAlsVers() {
        let cache = TimedCache<String>(ttl: 60)
        cache.seed("van schijf")
        XCTAssertEqual(cache.snapshot, "van schijf")
        XCTAssertNil(cache.fresh)
    }

    func testWaardeVanSchijfOverschrijftGeenNieuwereWaarde() {
        let cache = TimedCache<String>(ttl: 60)
        cache.put("van het netwerk")
        cache.seed("van schijf")
        XCTAssertEqual(cache.fresh, "van het netwerk")
    }

    func testSnapshotKomtOngeschondenTerug() {
        let rows = [CategoryRow(id: "nieuw", title: "Nieuw toegevoegd", moreURL: "https://x/nieuw",
                                items: [ProgramItem(id: "/p/a", slug: "a", title: "Één \"titel\"", thumbnailURL: "https://x/a.jpg",
                                                    badge: "Nieuw", nodeId: "42")])]
        let store = SnapshotStore()
        store.write(rows, "test-rows")
        XCTAssertEqual(store.read([CategoryRow].self, "test-rows"), rows)
        store.clear()
        XCTAssertNil(store.read([CategoryRow].self, "test-rows"))
    }

    /// Streampartner zet een mp4-bestand achter een HLS-playlist: alleen het einde van het pad telt.
    func testMimeTypeVoorCast() {
        XCTAssertEqual(PlaybackManager.mimeType(for: URL(string: "https://highvolume08.streampartner.nl/ondfam7/_definst_/video/Afl02.mp4/playlist.m3u8?t=1")!),
                       "application/x-mpegURL")
        XCTAssertEqual(PlaybackManager.mimeType(for: URL(string: "https://cdn.example.nl/oud/aflevering.MP4?token=abc")!),
                       "video/mp4")
    }

    func testNulDuurWordtVerborgen() {
        let episode = EpisodeItem(id: "a", episodeNumber: "1", title: "Doe niets", duration: "00 m", videoSlug: "a", videoURL: "")
        XCTAssertNil(episode.meaningfulDuration)
        XCTAssertEqual(EpisodeItem(id: "b", episodeNumber: "", title: "x", duration: "25 m", videoSlug: "b", videoURL: "").meaningfulDuration, "25 m")
    }
}
