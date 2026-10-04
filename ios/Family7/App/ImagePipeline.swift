import CryptoKit
import SwiftUI
import UIKit

/// Laadt omslagen met een cache in het geheugen en op schijf.
///
/// AsyncImage van SwiftUI volgt de cache-headers van de server, en Family7
/// stuurt vaak "no-cache": dan zou elke omslag bij elk scherm opnieuw over het
/// netwerk komen. Deze pijplijn bewaart ze zelf, zodat een koude start of een
/// tocht zonder netwerk meteen beeld heeft. Gelijktijdige verzoeken voor
/// hetzelfde adres delen één download.
actor ImagePipeline {
    static let shared = ImagePipeline()

    private let memory = NSCache<NSURL, UIImage>()
    private var inFlight: [URL: Task<Data?, Never>] = [:]
    private let directory: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("family7_images", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    init() {
        memory.totalCostLimit = 80 * 1024 * 1024
    }

    nonisolated func cachedImage(for url: URL) -> UIImage? {
        memory.object(forKey: url as NSURL)
    }

    func image(for url: URL) async -> UIImage? {
        if let cached = memory.object(forKey: url as NSURL) { return cached }
        guard let data = await data(for: url), let image = UIImage(data: data) else { return nil }
        // Vooraf decoderen, zodat scrollen niet hapert op het uitpakken.
        let prepared = await image.byPreparingForDisplay() ?? image
        memory.setObject(prepared, forKey: url as NSURL, cost: data.count)
        return prepared
    }

    func data(for url: URL) async -> Data? {
        let file = directory.appendingPathComponent(Self.key(url))
        if let data = try? Data(contentsOf: file) { return data }
        if let running = inFlight[url] { return await running.value }

        let task = Task<Data?, Never> {
            // Twee pogingen: een omslag hoort niet weg te blijven door één hapering.
            for attempt in 0..<2 {
                if let (data, response) = try? await URLSession.shared.data(from: url),
                   (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty {
                    try? data.write(to: file, options: .atomic)
                    return data
                }
                if attempt == 0 { try? await Task.sleep(nanoseconds: 700_000_000) }
            }
            return nil
        }
        inFlight[url] = task
        let result = await task.value
        inFlight[url] = nil
        return result
    }

    private static func key(_ url: URL) -> String {
        SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Een omslag met een glanzende plaatshouder tijdens het laden en het
/// Family7-beeldmerk als hij niet te laden is: nooit een leeg gat.
struct RemoteImage: View {
    let url: String
    var contentMode: ContentMode = .fill

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        // Color.clear neemt de maat die het scherm aanbiedt; de afbeelding vult
        // die alleen op. Zo duwt een brede omslag de opmaak nooit uit het scherm.
        Color.clear.overlay {
            content
        }
        .clipped()
        .task(id: url) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            } else if failed {
                Color.family7Surface
                    .overlay(Image("Family7Mark").resizable().scaledToFit().frame(width: 36).opacity(0.8))
            } else {
                ShimmerView()
            }
        }
    }

    private func load() async {
        guard let target = URL(string: url), !url.isEmpty else { failed = true; return }
        if let cached = ImagePipeline.shared.cachedImage(for: target) { image = cached; return }
        let loaded = await ImagePipeline.shared.image(for: target)
        withAnimation(.easeOut(duration: 0.2)) {
            image = loaded
            failed = loaded == nil
        }
    }
}
