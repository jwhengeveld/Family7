import AVKit
import SwiftUI

/// Een dag in de programmagids: de datum voor de site en het woord voor de kijker.
struct GuideDay: Hashable {
    let date: String
    let label: String

    /// Gisteren tot en met over zes dagen; de site heeft er niet meer.
    static func around() -> [GuideDay] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Amsterdam")!
        let format = DateFormatter()
        format.calendar = calendar
        format.timeZone = calendar.timeZone
        format.locale = Locale(identifier: "nl_NL")
        format.dateFormat = "EEE d MMM"
        return (-1...6).map { offset in
            let day = calendar.date(byAdding: .day, value: offset, to: Date()) ?? Date()
            let label: String
            switch offset {
            case -1: label = "Gisteren"
            case 0: label = "Vandaag"
            case 1: label = "Morgen"
            default: label = format.string(from: day).replacingOccurrences(of: ".", with: "").capitalized(with: Locale(identifier: "nl_NL"))
            }
            return GuideDay(date: LiveRepository.guideDate(offset), label: label)
        }
    }

    /// Minuten na middernacht in Nederland.
    static func nowMinutes() -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Amsterdam")!
        let parts = calendar.dateComponents([.hour, .minute], from: Date())
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }
}

/// Live tv zoals op de site: bovenaan een kleine speler met de uitzending, en
/// daaronder de programmagids (van de site), zodat te zien is wat wanneer komt.
/// De knop in de speler opent hem schermvullend.
struct LiveView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var days = GuideDay.around()
    @State private var selected = 1
    @State private var items: [GuideItem] = []
    @State private var isLoading = false
    @State private var error: String?
    @State private var nowMinutes = GuideDay.nowMinutes()
    @State private var fullscreen = InlinePlayerState()

    private var isToday: Bool { selected == 1 }
    private var currentIndex: Int? { isToday ? items.lastIndex { $0.startMinutes <= nowMinutes } : nil }

    var body: some View {
        GeometryReader { geometry in
            if sizeClass == .regular && geometry.size.width > geometry.size.height {
                // iPad liggend: speler links, gids rechts, zoals op tv.
                HStack(alignment: .top, spacing: 16) {
                    VStack(alignment: .leading, spacing: 0) {
                        playerArea.clipShape(RoundedRectangle(cornerRadius: 12))
                        nowCard
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity)
                    guide(showNow: false).frame(maxWidth: 440)
                }
                .padding(.horizontal, 16)
            } else {
                VStack(spacing: 0) {
                    playerArea
                    guide(showNow: true)
                }
            }
        }
        .background(Color.family7Background)
        .navigationTitle("Live tv")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                SearchButton()
                TVButtons()
            }
        }
        .onAppear {
            model.playback.playerScreenVisible = true
            // Meteen live kijken; speelt er al iets op de tv, dan blijft dat zo.
            if !model.playback.isCasting && model.playback.request != .live { model.playback.play(.live) }
        }
        .onDisappear {
            // Weg van dit scherm stopt de kleine speler, behalve schermvullend.
            guard !fullscreen.isFullscreen else { return }
            model.playback.playerScreenVisible = false
            model.playback.stopLocal()
        }
        .task(id: selected) { await load(force: false) }
        .task {
            await model.live.prefetchGuide()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                let minutes = GuideDay.nowMinutes()
                // Na middernacht is "vandaag" een andere dag.
                if minutes < nowMinutes { days = GuideDay.around(); await load(force: false) }
                nowMinutes = minutes
            }
        }
    }

    // MARK: speler

    @ViewBuilder
    private var playerArea: some View {
        let playback = model.playback
        ZStack {
            Color.black
            if playback.isCasting {
                RemoteImage(url: playback.artworkURL).opacity(0.35)
                VStack(spacing: 10) {
                    Image(systemName: "tv").font(.title)
                    Text(playback.isLive ? "Live tv speelt op \(playback.castDeviceName ?? "de tv")"
                                         : "Verbonden met \(playback.castDeviceName ?? "de tv")")
                        .font(.headline)
                    if playback.isLive {
                        Button("Bediening") { model.showPlayer = true }
                            .buttonStyle(.borderedProminent).tint(.family7Red)
                    } else {
                        Button("Live op de tv") { playback.play(.live) }
                            .buttonStyle(.borderedProminent).tint(.family7Red)
                    }
                }
                .foregroundStyle(.white)
                .padding()
            } else {
                // De speler van iOS zelf, met zijn knop voor schermvullend.
                InlineVideoPlayer(player: playback.player, state: fullscreen)
                if playback.isLoading && playback.player.currentItem == nil {
                    ProgressView().controlSize(.large).tint(.white)
                }
                if let error = playback.error {
                    VStack(spacing: 12) {
                        Text(error).multilineTextAlignment(.center)
                        Button("Opnieuw proberen", action: playback.retry)
                            .buttonStyle(.borderedProminent).tint(.family7Red)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black.opacity(0.8))
                    .foregroundStyle(.white)
                }
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
    }

    // MARK: gids

    @ViewBuilder
    private var nowCard: some View {
        if let index = currentIndex {
            let item = items[index]
            let next = items.indices.contains(index + 1) ? items[index + 1] : nil
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle().fill(Color.family7Red).frame(width: 8, height: 8)
                    Text("NU LIVE").font(.caption.weight(.bold)).foregroundStyle(Color.family7Red)
                    Text([item.start, next?.start].compactMap { $0 }.joined(separator: " – "))
                        .font(.caption).foregroundStyle(Color.family7Secondary)
                }
                Text(item.title).font(.title3.weight(.bold)).lineLimit(2)
                if !item.episode.isEmpty {
                    Text(item.episode).font(.subheadline).foregroundStyle(Color.family7Secondary).lineLimit(1)
                }
                if let fraction = progress(item, next) {
                    ProgressView(value: fraction).tint(.family7Red).padding(.top, 4)
                }
                if let next {
                    Text("Straks: \(next.start)  \(next.title)")
                        .font(.footnote).foregroundStyle(Color.family7Secondary).lineLimit(1).padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
    }

    private func guide(showNow: Bool) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                    if showNow { nowCard }
                    // De dagen blijven bovenaan staan tijdens het scrollen.
                    Section {
                        guideRows
                    } header: {
                        dayChips
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable { await load(force: true) }
            // Vandaag: de uitzending van nu in beeld, niet de nacht ervoor.
            .onChange(of: items) { scrollToNow(proxy) }
            // Ook na draaien (dan is dit een nieuwe lijst).
            .onAppear { scrollToNow(proxy) }
        }
    }

    private func scrollToNow(_ proxy: ScrollViewProxy) {
        guard let index = currentIndex else { return }
        DispatchQueue.main.async { proxy.scrollTo(max(index - 1, 0), anchor: .top) }
    }

    private var dayChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                    let isOn = index == selected
                    Button(day.label) { selected = index }
                        .font(.subheadline.weight(isOn ? .semibold : .regular))
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(isOn ? Color.family7Red : Color.family7Surface, in: Capsule())
                        .foregroundStyle(.white)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(Color.family7Background)
    }

    @ViewBuilder
    private var guideRows: some View {
        if items.isEmpty && isLoading {
            ForEach(0..<6, id: \.self) { _ in
                ShimmerView().frame(height: 52).clipShape(RoundedRectangle(cornerRadius: 6)).padding(.horizontal, 16).padding(.vertical, 6)
            }
        } else if items.isEmpty {
            VStack(spacing: 12) {
                Text(error ?? "Voor deze dag staat er nog niets in de gids.").foregroundStyle(Color.family7Secondary)
                if error != nil {
                    Button("Opnieuw proberen") { Task { await load(force: true) } }
                        .buttonStyle(.borderedProminent).tint(.family7Red)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(24)
        } else {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                let row = GuideRow(item: item,
                                   isNow: index == currentIndex,
                                   isPast: currentIndex.map { index < $0 } ?? false,
                                   progress: index == currentIndex ? progress(item, items.indices.contains(index + 1) ? items[index + 1] : nil) : nil)
                Group {
                    if item.programSlug.isEmpty {
                        row
                    } else {
                        NavigationLink(value: ProgramItem(id: item.programSlug, slug: item.programSlug,
                                                          title: item.title, thumbnailURL: item.imageURL)) { row }
                            .buttonStyle(.plain)
                    }
                }
                .id(index)
            }
        }
    }

    /// Hoe ver de uitzending is, tot het begin van de volgende (anders een half uur).
    private func progress(_ item: GuideItem, _ next: GuideItem?) -> Double? {
        let end = next.map(\.startMinutes).flatMap { $0 > item.startMinutes ? $0 : nil } ?? item.startMinutes + 30
        guard nowMinutes >= item.startMinutes, nowMinutes < end else { return nil }
        return Double(nowMinutes - item.startMinutes) / Double(end - item.startMinutes)
    }

    private func load(force: Bool) async {
        guard days.indices.contains(selected) else { return }
        let date = days[selected].date
        // Meteen tonen wat er al bekend is; daarna stil verversen van de site.
        if let cached = model.live.cachedGuide(date) { items = cached } else { items = [] }
        isLoading = true
        defer { isLoading = false }
        do {
            let fresh = try await model.live.guide(date, force: force)
            if days.indices.contains(selected), days[selected].date == date { items = fresh; error = nil }
        } catch {
            if items.isEmpty { self.error = friendlyError(error) }
        }
        // De dagen eromheen alvast ophalen: wisselen van dag is dan direct.
        for neighbour in [selected - 1, selected + 1] where days.indices.contains(neighbour) {
            _ = try? await model.live.guide(days[neighbour].date)
        }
    }
}

private struct GuideRow: View {
    let item: GuideItem
    let isNow: Bool
    let isPast: Bool
    let progress: Double?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.start).font(.subheadline.weight(.semibold)).foregroundStyle(isNow ? Color.family7Red : .white)
                if isNow { Text("NU").font(.caption2.weight(.bold)).foregroundStyle(Color.family7Red) }
            }
            .frame(width: 48, alignment: .leading)
            if !item.imageURL.isEmpty {
                RemoteImage(url: item.imageURL)
                    .frame(width: 96, height: 54)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.medium)).lineLimit(2)
                if !item.episode.isEmpty {
                    Text(item.episode).font(.caption).foregroundStyle(Color.family7Secondary).lineLimit(1)
                }
                if !item.description.isEmpty {
                    Text(item.description).font(.caption).foregroundStyle(Color.family7Secondary.opacity(0.7)).lineLimit(2)
                }
                if let progress {
                    ProgressView(value: progress).tint(.family7Red).padding(.top, 4)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(isNow ? Color.family7Surface : Color.clear)
        .opacity(isPast ? 0.55 : 1)
        .contentShape(Rectangle())
    }
}

/// Of de kleine speler schermvullend staat: dan stopt het verlaten van het
/// live-scherm de weergave niet.
@MainActor
final class InlinePlayerState {
    var isFullscreen = false
}

/// AVPlayerViewController in de pagina, met de knop voor schermvullend van iOS.
private struct InlineVideoPlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    let state: InlinePlayerState

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.updatesNowPlayingInfoCenter = false
        controller.videoGravity = .resizeAspect
        controller.entersFullScreenWhenPlaybackBegins = false
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
    }

    func makeCoordinator() -> Coordinator { Coordinator(state: state) }

    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        let state: InlinePlayerState
        init(state: InlinePlayerState) { self.state = state }

        func playerViewController(_ controller: AVPlayerViewController,
                                  willBeginFullScreenPresentationWithAnimationCoordinator coordinator: UIViewControllerTransitionCoordinator) {
            MainActor.assumeIsolated { state.isFullscreen = true }
        }

        func playerViewController(_ controller: AVPlayerViewController,
                                  willEndFullScreenPresentationWithAnimationCoordinator coordinator: UIViewControllerTransitionCoordinator) {
            coordinator.animate(alongsideTransition: nil) { _ in
                MainActor.assumeIsolated { self.state.isFullscreen = false }
            }
        }
    }
}
