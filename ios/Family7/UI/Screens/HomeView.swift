import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var loader: Loader<[CategoryRow]>?
    @State private var live: LiveStreamInfo?

    var body: some View {
        Group {
            if let loader {
                content(loader)
            } else {
                Color.clear
            }
        }
        .background(Color.family7Background)
        .navigationTitle("")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Image("Family7Logo").resizable().scaledToFit().frame(height: 28)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                TVButtons()
                Menu {
                    Button("Uitloggen", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { model.logout() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task {
            if loader == nil {
                let catalog = model.catalog
                // Meteen gevuld uit het geheugen of de snapshot van de vorige sessie.
                let created = Loader(initial: catalog.homeCache.snapshot) { force in
                    try await catalog.onDemandHome(force: force)
                }
                loader = created
                created.load()
            }
        }
        // Stil verversen zolang dit scherm zichtbaar is; .task stopt vanzelf
        // als het scherm verdwijnt of de app naar de achtergrond gaat.
        .task {
            while !Task.isCancelled {
                await refreshLive()
                try? await Task.sleep(nanoseconds: UInt64(backgroundRefreshInterval * 1_000_000_000))
                loader?.load(force: true)
            }
        }
        .onChange(of: model.network.reconnects) { loader?.networkRestored() }
    }

    @ViewBuilder
    private func content(_ loader: Loader<[CategoryRow]>) -> some View {
        if loader.showFullError {
            FullScreenError(message: loader.error ?? "") { loader.load(force: true) }
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8, pinnedViews: []) {
                    StatusBanner(isOffline: !model.network.isOnline,
                                 error: loader.value == nil ? nil : loader.error) { loader.load(force: true) }
                    if let rows = loader.value {
                        let featured = rows.first { $0.id == "uitgelicht" }?.items.first ?? rows.first?.items.first
                        if let featured { HeroCard(program: featured) }
                        LiveCard(info: live) { model.play(.live) }
                        HStack(spacing: 8) {
                            NavigationLink(value: GridDestination.kids) { Label("Kids", systemImage: "figure.and.child.holdinghands") }
                            NavigationLink(value: GridDestination.all) { Label("Alle programma's", systemImage: "textformat.abc") }
                        }
                        .buttonStyle(.bordered)
                        .font(.subheadline)
                        .padding(.horizontal, 16)
                        ForEach(rows.filter { $0.id != "uitgelicht" && !$0.items.isEmpty }) { row in
                            ProgramRow(row: row)
                        }
                    } else {
                        ShimmerView().aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 14)).padding(16)
                        ForEach(0..<3, id: \.self) { _ in SkeletonRow() }
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable {
                async let rows: Void = loader.refresh()
                async let info: Void = refreshLive()
                _ = await (rows, info)
            }
            .animation(.default, value: loader.value)
        }
    }

    /// "Nu op Family7" is een extraatje: mislukt het, dan blijft de kaart algemeen.
    private func refreshLive() async {
        if let info = try? await model.live.liveInfo() { live = info }
    }
}

private struct HeroCard: View {
    let program: ProgramItem

    var body: some View {
        NavigationLink(value: program) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: program.thumbnailURL)
                LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.85), location: 1)],
                               startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 4) {
                    Text("UITGELICHT").font(.caption.weight(.bold)).foregroundStyle(Color.family7Red)
                    Text(program.title).font(.title2.weight(.bold)).lineLimit(2)
                    if !program.description.isEmpty {
                        Text(program.description).font(.footnote).foregroundStyle(Color.family7Secondary).lineLimit(2)
                    }
                    Label("Bekijken", systemImage: "play.fill")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Color.family7Red, in: Capsule())
                        .padding(.top, 6)
                }
                .padding(16)
                .multilineTextAlignment(.leading)
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxHeight: 360)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .buttonStyle(.plain)
    }
}

struct LiveCard: View {
    let info: LiveStreamInfo?
    let onPlay: () -> Void

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 12) {
                Circle().fill(Color.family7Red).frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Nu live op Family7").font(.caption).foregroundStyle(Color.family7Secondary)
                    Text(info?.currentProgram.nonEmpty ?? "Kijk de uitzending live")
                        .font(.headline).lineLimit(1)
                    if let time = info?.timeRange.nonEmpty {
                        Text(time).font(.caption).foregroundStyle(Color.family7Secondary)
                    }
                }
                Spacer()
                Image(systemName: "play.fill").foregroundStyle(Color.family7Red)
            }
            .padding(14)
            .background(Color.family7Surface, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
    }
}

private struct ProgramRow: View {
    let row: CategoryRow

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(row.title).font(.headline)
                Spacer()
                if !row.moreURL.isEmpty {
                    NavigationLink("Alles", value: GridDestination.page(url: row.moreURL, title: row.title))
                        .font(.subheadline)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(row.items) { program in
                        NavigationLink(value: program) { ProgramCard(program: program) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}
