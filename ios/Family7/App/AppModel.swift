import Foundation
import Observation
import SwiftUI

enum AppTab: Hashable { case home, live, browse, kids, myList }

enum AuthState {
    case checking, loggedOut, loggedIn
}

/// Houdt de repositories, de sessie en de speler één keer bij voor de hele
/// app, zodat schermen hun cache delen en een cast-sessie doorloopt tussen schermen.
@MainActor
@Observable
final class AppModel {
    let auth = AuthRepository()
    let catalog = CatalogRepository()
    let video = VideoRepository()
    let live = LiveRepository()
    let myListRepository = MyListRepository()
    let network = NetworkMonitor()
    let playback: PlaybackManager

    private(set) var authState: AuthState = .checking
    private(set) var isLoggingIn = false
    private(set) var loginError: String?
    private(set) var myList: [ProgramItem] = []
    private(set) var myListLoaded = false
    @ObservationIgnored private var sessionObserver: NSObjectProtocol?
    @ObservationIgnored private var checkingSession = false
    /// Het spelerscherm wordt getoond (als fullScreenCover).
    var showPlayer = false
    /// Het gekozen tabblad en de navigatie op het startscherm, voor deep links.
    var selectedTab: AppTab = .home
    var homePath = NavigationPath()

    init() {
        playback = PlaybackManager(video: video, live: live)
        // De catalogus van de vorige keer klaarzetten voor het eerste scherm.
        catalog.restoreSnapshots()

        // Gaf Family7 ergens een anonieme pagina of de inlogpagina terug, dan
        // laten we Family7 de sessie bevestigen; alleen als die echt voorbij
        // is, gaat de gebruiker naar het aanmeldscherm.
        sessionObserver = NotificationCenter.default.addObserver(
            forName: .family7SessionExpired, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.authState == .loggedIn, !self.checkingSession else { return }
                self.checkingSession = true
                if await !self.auth.confirmSession() { self.signedOut() }
                self.checkingSession = false
            }
        }

        if auth.hasStoredSession {
            // Meteen naar binnen; Family7 bevestigt de sessie op de achtergrond.
            authState = .loggedIn
            Task {
                await refreshMyList()
                if await !auth.confirmSession() { signedOut() }
            }
        } else {
            authState = .loggedOut
        }
    }

    func login(email: String, password: String) {
        guard !isLoggingIn else { return }
        isLoggingIn = true
        loginError = nil
        Task {
            do {
                try await auth.login(email: email, password: password)
                authState = .loggedIn
                await refreshMyList()
            } catch {
                loginError = friendlyError(error)
            }
            isLoggingIn = false
        }
    }

    func logout() {
        playback.stopCasting()
        playback.stopLocal()
        auth.logout()
        signedOut()
    }

    private func signedOut() {
        myList = []
        myListLoaded = false
        catalog.clear()
        showPlayer = false
        authState = .loggedOut
    }

    // MARK: afspelen

    func play(_ request: PlayRequest) {
        playback.play(request)
        // Tijdens het casten blijft de kijker waar hij is; de mini-balk toont wat er speelt.
        if !playback.isCasting { showPlayer = true }
    }

    // MARK: deep links

    /// family7://live, family7://programma/<slug>[/afspelen] en family7://kids.
    func open(_ url: URL) {
        guard url.scheme == "family7", authState == .loggedIn else { return }
        let parts = url.pathComponents.filter { $0 != "/" }
        switch url.host {
        case "live":
            play(.live)
        case "programma", "program":
            guard let slug = parts.first else { return }
            // family7://programma/<slug>/afspelen start meteen de eerste aflevering.
            if parts.dropFirst().first == "afspelen" {
                Task {
                    guard let detail = try? await video.programDetail(slug),
                          let first = detail.seasons.first?.episodes.first else { return }
                    play(.episode(first, detail))
                }
                return
            }
            showPlayer = false
            selectedTab = .home
            homePath = NavigationPath()
            homePath.append(ProgramItem(id: slug, slug: slug, title: Family7URL.titleFromSlug(slug), thumbnailURL: ""))
        case "kids":
            selectedTab = .home
            homePath = NavigationPath()
            homePath.append(GridDestination.kids)
        default:
            selectedTab = .home
        }
    }

    // MARK: Mijn lijst

    func refreshMyList() async {
        if let items = try? await myListRepository.fetch() {
            myList = items
            myListLoaded = true
        }
    }

    func isInMyList(_ slug: String) -> Bool { myList.contains { $0.slug == slug } }

    /// De knop reageert meteen; Family7 bevestigt op de achtergrond, en bij een
    /// fout gaat de knop terug.
    func setInMyList(_ program: ProgramDetail, add: Bool) {
        let item = ProgramItem(id: program.slug, slug: program.slug, title: program.title,
                               thumbnailURL: program.posterURL, nodeId: program.nodeId)
        let previous = myList
        if add { if !isInMyList(program.slug) { myList.insert(item, at: 0) } }
        else { myList.removeAll { $0.slug == program.slug } }
        Task {
            do {
                _ = try await myListRepository.setInList(nodeId: program.nodeId, add: add)
                await refreshMyList()
            } catch {
                myList = previous
            }
        }
    }
}
