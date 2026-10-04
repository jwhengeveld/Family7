import SwiftUI

@main
struct Family7App: App {
    @State private var model: AppModel

    init() {
        // Cast eerst: de speler meldt zich bij de Cast-sessies aan.
        CastSetup.start()
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(.family7Red)
        }
    }
}

/// Op welke overzichtspagina een rij met programma's uitkomt.
enum GridDestination: Hashable {
    case kids
    case all
    case page(url: String, title: String)
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var splashDone = false

    var body: some View {
        @Bindable var model = model
        ZStack {
        Group {
            switch model.authState {
            case .checking:
                Color.family7Background.ignoresSafeArea()
                    .overlay(Image("Family7Logo").resizable().scaledToFit().frame(width: 160))
            case .loggedOut:
                LoginView()
            case .loggedIn:
                MainTabs()
            }
        }
        // De onthulling ligt over de app heen; die laadt eronder al door.
        if !splashDone {
            SplashView(ready: model.authState != .checking) { splashDone = true }
                .zIndex(1)
        }
        }
        .fullScreenCover(isPresented: $model.showPlayer) {
            PlayerView()
        }
        .onChange(of: model.network.reconnects) {
            model.playback.networkRestored()
        }
        .onOpenURL { model.open($0) }
        #if DEBUG
        // Alleen voor testen: een deep link als opstartargument, zodat een
        // simulator zonder scherm de app kan besturen:
        // xcrun simctl launch <sim> nl.family7.ios -family7DeepLink family7://live
        .task(id: model.authState == .loggedIn) {
            guard model.authState == .loggedIn,
                  let link = UserDefaults.standard.string(forKey: "family7DeepLink"),
                  let url = URL(string: link) else { return }
            model.open(url)
        }
        #endif
    }
}

struct MainTabs: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            NavigationStack(path: $model.homePath) { HomeView().family7Destinations() }.miniCastBar()
                .tabItem { Label("Start", systemImage: "house.fill") }.tag(AppTab.home)
            NavigationStack { LiveView() }.miniCastBar()
                .tabItem { Label("Live", systemImage: "dot.radiowaves.left.and.right") }.tag(AppTab.live)
            NavigationStack { SearchView().family7Destinations() }.miniCastBar()
                .tabItem { Label("Zoeken", systemImage: "magnifyingglass") }.tag(AppTab.search)
            NavigationStack { MyListView().family7Destinations() }.miniCastBar()
                .tabItem { Label("Mijn lijst", systemImage: "bookmark.fill") }.tag(AppTab.myList)
        }
    }
}

extension View {
    /// Programmapagina's en overzichten zijn vanuit elk tabblad te openen.
    func family7Destinations() -> some View {
        navigationDestination(for: ProgramItem.self) { ProgramView(preview: $0) }
            .navigationDestination(for: GridDestination.self) { GridView(destination: $0) }
    }

    /// Tijdens het casten een balk onderin, zodat de bediening binnen bereik blijft.
    func miniCastBar() -> some View {
        modifier(MiniCastBarModifier())
    }
}

private struct MiniCastBarModifier: ViewModifier {
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            // Tijdens het casten altijd, ook als er nog niets speelt.
            if model.playback.isCasting {
                MiniCastBar()
            }
        }
    }
}

struct MiniCastBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let playback = model.playback
        let playing = playback.request != nil
        VStack(spacing: 0) {
            if playing && !playback.isLive {
                ProgressView(value: playback.castDuration > 0 ? min(playback.castPosition / playback.castDuration, 1) : 0)
                    .tint(.family7Red)
                    .scaleEffect(x: 1, y: 0.6)
            }
            HStack(spacing: 12) {
                if playing {
                    RemoteImage(url: playback.artworkURL)
                        .frame(width: 64, height: 36)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                } else {
                    Image("Family7Mark").resizable().scaledToFit().frame(width: 40, height: 36)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(playing ? playback.title : "Klaar om te casten").font(.subheadline).lineLimit(1)
                    Label(playback.castDeviceName ?? "Chromecast", systemImage: "tv")
                        .font(.caption)
                        .foregroundStyle(Color.family7Secondary)
                        .lineLimit(1)
                }
                Spacer()
                if playing {
                    Button { playback.togglePlayPause() } label: {
                        Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill").font(.title3)
                    }
                    .accessibilityLabel(playback.isPlaying ? "Pauzeren" : "Afspelen")
                }
                Button { playback.stopCasting() } label: {
                    Image(systemName: "xmark").font(.subheadline.weight(.semibold)).foregroundStyle(Color.family7Secondary)
                }
                .accessibilityLabel("Stoppen met casten")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(.ultraThinMaterial)
        .contentShape(Rectangle())
        .onTapGesture { model.showPlayer = true }
    }
}
