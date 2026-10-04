import SwiftUI

struct ProgramView: View {
    /// Wat er al bekend is van de kaart waarop getikt werd, voor een meteen gevulde kop.
    let preview: ProgramItem

    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var loader: Loader<ProgramDetail>?
    @State private var selectedSeason: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let loader {
                    StatusBanner(isOffline: !model.network.isOnline,
                                 error: loader.value == nil ? nil : loader.error) { loader.load(force: true) }
                    if let detail = loader.value {
                        details(detail)
                    } else if loader.showFullError {
                        FullScreenError(message: loader.error ?? "") { loader.load(force: true) }.frame(height: 300)
                    } else {
                        skeleton
                    }
                }
            }
        }
        .background(Color.family7Background)
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
        .refreshable { await loader?.refresh() }
        .task {
            guard loader == nil else { return }
            let video = model.video
            let slug = preview.slug
            let created = Loader(initial: video.cachedDetail(slug)) { _ in try await video.programDetail(slug) }
            loader = created
            created.load()
        }
        // Het stream-adres van de eerste aflevering alvast opzoeken: dan start
        // "Afspelen" zonder wachttijd.
        .task(id: loader?.value?.seasons.first?.episodes.first?.videoSlug) {
            if let first = loader?.value?.seasons.first?.episodes.first {
                await model.video.prefetchStreamURL(first.videoSlug)
            }
        }
        .onChange(of: model.network.reconnects) { loader?.networkRestored() }
    }

    private var header: some View {
        let detail = loader?.value
        return ZStack(alignment: .bottomLeading) {
            RemoteImage(url: detail?.posterURL.nonEmpty ?? preview.thumbnailURL)
            LinearGradient(stops: [.init(color: .black.opacity(0.4), location: 0), .init(color: .clear, location: 0.35),
                                   .init(color: .family7Background, location: 1)],
                           startPoint: .top, endPoint: .bottom)
            Text(detail?.title ?? preview.title)
                .font(.title.weight(.bold))
                .lineLimit(2)
                .padding(16)
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .frame(maxWidth: .infinity, maxHeight: 380)
        .clipped()
    }

    @ViewBuilder
    private func details(_ detail: ProgramDetail) -> some View {
        let episodes = detail.seasons.flatMap(\.episodes)
        // De lijst van het account is leidend; is die nog niet geladen, dan wat
        // de programmapagina van Family7 zelf aangeeft.
        let inList = model.myListLoaded ? model.isInMyList(detail.slug) : detail.isInMyList

        VStack(alignment: .leading, spacing: 12) {
            if !detail.category.isEmpty {
                Text(detail.category).font(.caption).foregroundStyle(Color.family7Secondary)
            }
            HStack(spacing: 8) {
                if let first = episodes.first {
                    Button {
                        model.play(.episode(first, detail))
                    } label: {
                        Label(model.playback.isCasting ? "Afspelen op \(model.playback.castDeviceName ?? "de tv")" : "Afspelen",
                              systemImage: model.playback.isCasting ? "tv" : "play.fill")
                            .lineLimit(1)
                            // Op een iPad een knop, geen balk over de hele breedte.
                            .frame(minWidth: sizeClass == .regular ? 180 : nil, maxWidth: sizeClass == .regular ? nil : .infinity, minHeight: 30)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.family7Red)
                }
                if !detail.nodeId.isEmpty {
                    Button {
                        model.setInMyList(detail, add: !inList)
                    } label: {
                        Label(inList ? "In mijn lijst" : "Mijn lijst", systemImage: inList ? "bookmark.fill" : "bookmark")
                            .frame(minHeight: 30)
                    }
                    .buttonStyle(.bordered)
                }
            }
            if !detail.description.isEmpty {
                Text(detail.description)
                    .font(sizeClass == .regular ? .body : .subheadline)
                    .foregroundStyle(Color.family7Secondary)
                    .frame(maxWidth: 760, alignment: .leading)
            }
        }
        .padding(16)

        // Eén seizoen tegelijk, met een keuze erboven: series als "Bijbelse
        // karakters" hebben dertien seizoenen.
        let shown = detail.seasons.first { $0.seasonNumber == selectedSeason } ?? detail.seasons.first
        if detail.seasons.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(detail.seasons, id: \.seasonNumber) { season in
                        let isOn = season.seasonNumber == shown?.seasonNumber
                        Button(season.title) { selectedSeason = season.seasonNumber }
                            .font(.subheadline.weight(isOn ? .semibold : .regular))
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(isOn ? Color.family7Red : Color.family7Surface, in: Capsule())
                            .foregroundStyle(.white)
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.top, 8)
        }
        ForEach(shown.map { [$0] } ?? [], id: \.seasonNumber) { season in
            Text(detail.seasons.count > 1 ? "\(season.title) · \(season.episodes.count) \(season.episodes.count == 1 ? "aflevering" : "afleveringen")" : season.title)
                .font(.headline).padding(.horizontal, 16).padding(.top, 12)
            ForEach(season.episodes) { episode in
                Button { model.play(.episode(episode, detail)) } label: { EpisodeRow(episode: episode) }
                    .buttonStyle(.plain)
            }
        }
        if episodes.isEmpty {
            Text("Er staan nog geen afleveringen van dit programma online.")
                .foregroundStyle(Color.family7Secondary).padding(16)
        }
        Spacer(minLength: 32)
    }

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: 16) {
            ShimmerView().frame(height: 44).clipShape(Capsule())
            ForEach(0..<4, id: \.self) { _ in
                HStack(spacing: 12) {
                    ShimmerView().frame(width: 132, height: 74).clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 8) {
                        ShimmerView().frame(height: 16).clipShape(RoundedRectangle(cornerRadius: 4))
                        ShimmerView().frame(width: 80, height: 12).clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct EpisodeRow: View {
    let episode: EpisodeItem
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RemoteImage(url: episode.thumbnailURL)
                .frame(width: sizeClass == .regular ? 220 : 132, height: sizeClass == .regular ? 124 : 74)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(Image(systemName: "play.fill").padding(6).background(.black.opacity(0.5), in: Circle()))
            VStack(alignment: .leading, spacing: 3) {
                Text(episode.displayLabel).font(.subheadline.weight(.medium)).lineLimit(2)
                if let duration = episode.meaningfulDuration {
                    Text(duration).font(.caption).foregroundStyle(Color.family7Secondary)
                }
                if !episode.description.isEmpty {
                    Text(episode.description).font(.caption).foregroundStyle(Color.family7Secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}
