import SwiftUI

/// Kids, alle programma's, of de "Alles"-pagina van een rij.
struct GridView: View {
    let destination: GridDestination

    @Environment(AppModel.self) private var model
    @State private var loader: Loader<[ProgramItem]>?

    var body: some View {
        ScrollView {
            if let loader {
                StatusBanner(isOffline: !model.network.isOnline,
                             error: loader.value == nil ? nil : loader.error) { loader.load(force: true) }
                if loader.showFullError {
                    FullScreenError(message: loader.error ?? "") { loader.load(force: true) }.frame(height: 400)
                } else {
                    ProgramGrid(programs: loader.value, emptyMessage: "Hier staan nu geen programma's.")
                }
            }
        }
        .background(Color.family7Background)
        .navigationTitle(title)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
        .refreshable { await loader?.refresh() }
        .task {
            guard loader == nil else { return }
            let catalog = model.catalog
            let created: Loader<[ProgramItem]>
            switch destination {
            case .kids:
                created = Loader(initial: catalog.kidsCache.snapshot) { try await catalog.kidsPrograms(force: $0) }
            case .all:
                created = Loader(initial: catalog.azCache.snapshot) { try await catalog.allPrograms(force: $0) }
            case let .page(url, _):
                created = Loader { _ in try await catalog.programs(from: url) }
            }
            loader = created
            created.load()
        }
        .onChange(of: model.network.reconnects) { loader?.networkRestored() }
    }

    private var title: String {
        switch destination {
        case .kids: "Kids"
        case .all: "Alle programma's"
        case let .page(_, title): title
        }
    }
}

/// Zoeken filtert de complete A-Z-lijst op het toestel zelf: geen wachttijd per
/// letter, en ook zonder netwerk doorzoekbaar zodra de lijst er één keer is.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var loader: Loader<[ProgramItem]>?
    @State private var query = ""

    var body: some View {
        ScrollView {
            if let loader {
                StatusBanner(isOffline: !model.network.isOnline,
                             error: loader.value == nil ? nil : loader.error) { loader.load(force: true) }
                if loader.showFullError {
                    FullScreenError(message: loader.error ?? "") { loader.load(force: true) }.frame(height: 400)
                } else {
                    ProgramGrid(programs: loader.value.map(filter),
                                emptyMessage: query.isEmpty ? "Er zijn geen programma's gevonden." : "Niets gevonden voor \"\(query)\".")
                }
            }
        }
        .background(Color.family7Background)
        .navigationTitle("Zoeken")
        .searchable(text: $query, prompt: "Zoek een programma")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
        .refreshable { await loader?.refresh() }
        .task {
            guard loader == nil else { return }
            let catalog = model.catalog
            let created = Loader(initial: catalog.azCache.snapshot) { try await catalog.allPrograms(force: $0) }
            loader = created
            created.load()
        }
        .onChange(of: model.network.reconnects) { loader?.networkRestored() }
    }

    private func filter(_ all: [ProgramItem]) -> [ProgramItem] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return all }
        let slugNeedle = needle.replacingOccurrences(of: " ", with: "-")
        return all.filter { $0.title.lowercased().contains(needle) || $0.slug.contains(slugNeedle) }
    }
}

struct MyListView: View {
    @Environment(AppModel.self) private var model
    @State private var isLoading = false

    var body: some View {
        ScrollView {
            StatusBanner(isOffline: !model.network.isOnline, error: nil)
            ProgramGrid(
                programs: model.myListLoaded || !model.myList.isEmpty ? model.myList : nil,
                emptyMessage: "Uw lijst is nog leeg. Open een programma en kies \"Mijn lijst\"; die lijst deelt u met de website van Family7."
            )
        }
        .background(Color.family7Background)
        .navigationTitle("Mijn lijst")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
        .refreshable { await model.refreshMyList() }
        .task { await model.refreshMyList() }
    }
}
