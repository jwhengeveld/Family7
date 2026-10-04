import SwiftUI

/// Kids, alle programma's, of de "Alles"-pagina van een rij.
struct GridView: View {
    let destination: GridDestination

    @Environment(AppModel.self) private var model

    var body: some View {
        GridContent(destination: destination)
        .background(Color.family7Background)
        .navigationTitle(title)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
    }

    private var title: String {
        switch destination {
        case .kids: "Kids"
        case .all: "Alle programma's"
        case let .page(_, title): title
        }
    }
}

/// Het raster van één bron, met laden, verversen (omlaag vegen) en foutmeldingen.
struct GridContent: View {
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
            } else {
                ProgramGrid(programs: nil, emptyMessage: "")
            }
        }
        .refreshable { await loader?.refresh() }
        .task(id: destination) {
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
}

/// Bladeren door alle programma's, zoals "On Demand" op tv: A-Z, Kids en de
/// rubrieken die op de site staan. Die rubrieken komen van de site zelf, dus
/// een nieuwe rubriek verschijnt hier vanzelf.
struct BrowseView: View {
    @Environment(AppModel.self) private var model
    @State private var rows: [CategoryRow] = []
    @State private var selected = "az"

    private struct Choice: Identifiable {
        let id: String
        let label: String
        let destination: GridDestination?
        let items: [ProgramItem]
    }

    private var choices: [Choice] {
        var seen = Set<String>()
        let fromSite = rows
            .filter { $0.id != "uitgelicht" && (!$0.moreURL.isEmpty || !$0.items.isEmpty) }
            .filter { seen.insert($0.moreURL.isEmpty ? $0.id : $0.moreURL).inserted }
            .map { row in
                row.moreURL.isEmpty
                    ? Choice(id: "row-\(row.id)", label: row.title, destination: nil, items: row.items)
                    // Een rubriek zonder eigen "Alles"-pagina: dan de programma's uit de rij zelf.
                    : Choice(id: "page-\(row.moreURL)", label: row.title,
                             destination: .page(url: row.moreURL, title: row.title), items: [])
            }
        return [Choice(id: "az", label: "A-Z", destination: .all, items: []),
                Choice(id: "kids", label: "Kids", destination: .kids, items: [])] + fromSite
    }

    var body: some View {
        let all = choices
        let current = all.first { $0.id == selected } ?? all[0]
        // De keuzes blijven bovenaan staan; alleen het raster scrolt.
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(all) { choice in
                            let isOn = choice.id == current.id
                            Button(choice.label) { selected = choice.id }
                                .font(.subheadline.weight(isOn ? .semibold : .regular))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(isOn ? Color.family7Red : Color.family7Surface, in: Capsule())
                                .foregroundStyle(.white)
                                .id(choice.id)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                // De gekozen rubriek in beeld houden, ook als de lijst later binnenkomt.
                .onAppear { proxy.scrollTo(current.id, anchor: .center) }
                .onChange(of: rows.count) { proxy.scrollTo(current.id, anchor: .center) }
            }
            .padding(.vertical, 8)

            if let destination = current.destination {
                GridContent(destination: destination).id(current.id)
            } else {
                ScrollView { ProgramGrid(programs: current.items, emptyMessage: "Hier staan nu geen programma's.") }
            }
        }
        .background(Color.family7Background)
        .navigationTitle("Programma's")
        .toolbar { ToolbarItemGroup(placement: .topBarTrailing) { SearchButton(); TVButtons() } }
        .task {
            let catalog = model.catalog
            if let cached = catalog.homeCache.snapshot { rows = cached }
            if let fresh = try? await catalog.onDemandHome(force: false) { rows = fresh }
        }
    }
}

/// Naar het zoekscherm, als waarde in de navigatie.
struct SearchRoute: Hashable {}

/// Zoeken als rond knopje met vergrootglas rechtsboven, zoals gebruikelijk.
struct SearchButton: View {
    var body: some View {
        NavigationLink(value: SearchRoute()) {
            Image(systemName: "magnifyingglass")
        }
        .accessibilityLabel("Zoeken")
    }
}

/// Zoeken met suggesties: het zoekveld staat meteen open, daaronder suggesties
/// terwijl je typt. Zonder zoekwoord staan de laatste zoekopdrachten er.
/// Gefilterd wordt op het toestel zelf, dus ook zonder netwerk zodra de lijst
/// er één keer is.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var loader: Loader<[ProgramItem]>?
    @State private var query = ""
    @State private var isPresented = false
    @AppStorage("family7.search.recent") private var recentText = ""

    private var recent: [String] { recentText.split(separator: "\n").map(String.init) }

    var body: some View {
        List {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                if recent.isEmpty {
                    Text("Typ de naam van een programma. Suggesties verschijnen terwijl je typt.")
                        .foregroundStyle(Color.family7Secondary)
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(recent, id: \.self) { text in
                            Button { query = text } label: {
                                Label(text, systemImage: "clock.arrow.circlepath").foregroundStyle(.white)
                            }
                            .listRowBackground(Color.clear)
                        }
                    } header: {
                        HStack {
                            Text("Recent gezocht")
                            Spacer()
                            Button("Wissen") { recentText = "" }.font(.footnote)
                        }
                    }
                }
            } else if let all = loader?.value {
                let suggestions = ProgramItem.suggestions(all, for: query)
                if suggestions.isEmpty {
                    Text("Niets gevonden voor \"\(query.trimmingCharacters(in: .whitespaces))\".")
                        .foregroundStyle(Color.family7Secondary)
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(suggestions) { program in
                        NavigationLink(value: program) {
                            HStack(spacing: 12) {
                                RemoteImage(url: program.thumbnailURL)
                                    .frame(width: 96, height: 54)
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                                Text(program.title).lineLimit(2)
                            }
                        }
                        .simultaneousGesture(TapGesture().onEnded { remember(query) })
                        .listRowBackground(Color.clear)
                    }
                }
            } else if let loader, loader.showFullError {
                FullScreenError(message: loader.error ?? "") { loader.load(force: true) }
                    .listRowBackground(Color.clear)
            } else {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.family7Background)
        .navigationTitle("Zoeken")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, isPresented: $isPresented,
                    placement: .navigationBarDrawer(displayMode: .always), prompt: "Zoek een programma")
        .onSubmit(of: .search) { remember(query) }
        .onAppear { isPresented = true }
        .task {
            guard loader == nil else { return }
            let catalog = model.catalog
            let created = Loader(initial: catalog.azCache.snapshot) { try await catalog.allPrograms(force: $0) }
            loader = created
            created.load()
        }
        .onChange(of: model.network.reconnects) { loader?.networkRestored() }
    }

    /// Onthoudt een zoekopdracht bovenaan, zonder dubbele; alleen op dit toestel.
    private func remember(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespaces)
        guard clean.count >= 2 else { return }
        let updated = [clean] + recent.filter { $0.caseInsensitiveCompare(clean) != .orderedSame }
        recentText = updated.prefix(8).joined(separator: "\n")
    }
}

extension ProgramItem {
    /// Suggesties voor een zoekopdracht: titels die ermee beginnen eerst, dan
    /// titels met een woord dat ermee begint, dan de rest die het bevat.
    static func suggestions(_ all: [ProgramItem], for query: String, limit: Int = 30) -> [ProgramItem] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return [] }
        let slugNeedle = needle.replacingOccurrences(of: " ", with: "-")
        let ranked: [(Int, ProgramItem)] = all.compactMap { program in
            let title = program.title.lowercased()
            if title.hasPrefix(needle) { return (0, program) }
            let words = title.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "'" })
            if words.contains(where: { $0.hasPrefix(needle) }) { return (1, program) }
            if title.contains(needle) || program.slug.contains(slugNeedle) { return (2, program) }
            return nil
        }
        return ranked
            .sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1.title.lowercased() < $1.1.title.lowercased() }
            .prefix(limit)
            .map(\.1)
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
        .toolbar { ToolbarItemGroup(placement: .topBarTrailing) { SearchButton(); TVButtons() } }
        .refreshable { await model.refreshMyList() }
        .task { await model.refreshMyList() }
    }
}
