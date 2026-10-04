import AVFoundation
import Foundation
import GoogleCast
import MediaPlayer
import Observation
import UIKit

/// Wat er afgespeeld moet worden; het stream-adres wordt pas bij het afspelen opgezocht.
enum PlayRequest: Hashable {
    case episode(EpisodeItem, ProgramDetail)
    case live
}

/// Speelt af op de telefoon (en via AirPlay op een Apple TV) of op een
/// Chromecast, en wisselt daartussen zonder dat de kijker zijn plek kwijtraakt.
///
/// AirPlay hoeft niets bijzonders: AVPlayer stuurt de video zelf door zodra
/// iemand in de bediening of het bedieningspaneel een Apple TV kiest.
/// Google Cast gaat via de Cast SDK: de tv haalt de stream zelf op en de
/// telefoon wordt de afstandsbediening.
///
/// Robuust laden, zoals in de Android-app:
/// - mislukt een stream, dan een vers stream-adres (het token van Streampartner
///   verloopt) en verder op dezelfde plek, met oplopende pauzes;
/// - mislukte het door een wegvallend netwerk, dan vanzelf opnieuw zodra de
///   verbinding terug is;
/// - weigert de Chromecast de stream, dan eerst een vers adres; lukt het dan nog
///   niet, dan zegt de app dat eerlijk en biedt hij aan op de telefoon te kijken.
@MainActor
@Observable
final class PlaybackManager: NSObject {

    // MARK: toestand voor de schermen

    private(set) var request: PlayRequest?
    private(set) var title = ""
    private(set) var subtitle = ""
    private(set) var artworkURL = ""
    private(set) var isLive = false
    private(set) var isLoading = false
    private(set) var isPlaying = false
    private(set) var error: String?
    private(set) var isCasting = false
    private(set) var castDeviceName: String?
    private(set) var isAirPlaying = false
    /// Positie en duur tijdens het casten, voor de afstandsbediening.
    private(set) var castPosition: TimeInterval = 0
    private(set) var castDuration: TimeInterval = 0
    /// Volume van de tv (0...1) en of hij gedempt is.
    private(set) var castVolume: Float = 0.5
    private(set) var castMuted = false
    /// Of er een vorige of volgende aflevering is in dit programma.
    private(set) var hasPrevious = false
    private(set) var hasNext = false

    /// De lokale speler; AVPlayerViewController toont hem en doet AirPlay en beeld-in-beeld.
    let player = AVPlayer()

    // MARK: intern

    private let video: VideoRepository
    private let live: LiveRepository
    private var currentStreamURL: URL?
    private var loadTask: Task<Void, Never>?
    private var recoveryAttempts = 0
    private var observations: [NSKeyValueObservation] = []
    private var itemObservers: [NSObjectProtocol] = []
    private var itemStatusObservation: NSKeyValueObservation?

    /// Of het spelerscherm in beeld is; bepaalt wat er gebeurt als het casten stopt.
    var playerScreenVisible = false
    private var castTimer: Timer?
    private var artworkData: Data?

    private static let maxRecoveryAttempts = 2

    init(video: VideoRepository, live: LiveRepository) {
        self.video = video
        self.live = live
        super.init()
        configureAudioSession()
        observePlayer()
        configureRemoteCommands()
        GCKCastContext.sharedInstance().sessionManager.add(self)
        // Een sessie die al liep (de app werd herstart tijdens het casten).
        if let session = GCKCastContext.sharedInstance().sessionManager.currentCastSession {
            attach(to: session)
        }
    }

    // MARK: - afspelen

    func play(_ request: PlayRequest) {
        recoveryAttempts = 0
        self.request = request
        let (previous, next) = neighbours(request)
        hasPrevious = previous != nil
        hasNext = next != nil
        error = nil
        isLoading = true
        artworkData = nil
        switch request {
        case let .episode(episode, program):
            title = program.title
            subtitle = episode.displayLabel
            artworkURL = episode.thumbnailURL.nonEmpty ?? program.posterURL
            isLive = false
        case .live:
            title = "Family7 Live"
            subtitle = "Live"
            artworkURL = ""
            isLive = true
        }
        load(request, forceFresh: false, startAt: nil)
    }

    /// Opnieuw proberen na een fout: een vers stream-adres, vanaf dezelfde plek.
    func retry() {
        guard let request else { return }
        recoveryAttempts = 0
        error = nil
        isLoading = true
        load(request, forceFresh: true, startAt: resumePosition)
    }

    /// Stopt de weergave op de telefoon. Een cast-sessie speelt door op de tv.
    func stopLocal() {
        guard !isCasting else { return }
        loadTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        reset()
    }

    func togglePlayPause() {
        if isCasting {
            guard let client = remoteClient else { return }
            if isPlaying { client.pause() } else { client.play() }
        } else {
            if player.timeControlStatus == .paused { player.play() } else { player.pause() }
        }
    }

    func seek(to seconds: TimeInterval) {
        if isCasting {
            let options = GCKMediaSeekOptions()
            options.interval = seconds
            remoteClient?.seek(with: options)
            castPosition = seconds
        } else {
            player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        }
    }

    func skip(by seconds: TimeInterval) {
        let now = isCasting ? castPosition : player.currentTime().seconds
        seek(to: max(0, now + seconds))
    }

    /// Stopt met casten; de weergave gaat gepauzeerd verder op de telefoon.
    func stopCasting() {
        GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
    }

    // MARK: - bediening

    /// Terug naar de live-rand: op de tv via "zoek naar oneindig", lokaal via de speler.
    func goLive() {
        if isCasting, let client = remoteClient {
            let options = GCKMediaSeekOptions()
            options.seekToInfinite = true
            client.seek(with: options)
        } else if let range = player.currentItem?.seekableTimeRanges.last?.timeRangeValue {
            player.seek(to: range.end)
        }
    }

    func setCastVolume(_ volume: Float) {
        let clamped = min(max(volume, 0), 1)
        GCKCastContext.sharedInstance().sessionManager.currentCastSession?.setDeviceVolume(clamped)
        castVolume = clamped
        castMuted = false
    }

    func toggleCastMute() {
        castMuted.toggle()
        GCKCastContext.sharedInstance().sessionManager.currentCastSession?.setDeviceMuted(castMuted)
    }

    func playNext() {
        guard let request, let next = neighbours(request).next else { return }
        play(next)
    }

    func playPrevious() {
        guard let request, let previous = neighbours(request).previous else { return }
        play(previous)
    }

    /// Vorige en volgende aflevering in kijkvolgorde: binnen het seizoen op
    /// nummer, en aan het eind door naar het volgende seizoen.
    private func neighbours(_ request: PlayRequest) -> (previous: PlayRequest?, next: PlayRequest?) {
        guard case let .episode(episode, program) = request else { return (nil, nil) }
        let ordered = program.seasons
            .sorted { (Int($0.seasonNumber) ?? .max) < (Int($1.seasonNumber) ?? .max) }
            .flatMap(\.episodes)
        guard let index = ordered.firstIndex(where: { $0.videoSlug == episode.videoSlug }) else { return (nil, nil) }
        let previous = index > 0 ? PlayRequest.episode(ordered[index - 1], program) : nil
        let next = index + 1 < ordered.count ? PlayRequest.episode(ordered[index + 1], program) : nil
        return (previous, next)
    }

    // MARK: - laden

    private func load(_ request: PlayRequest, forceFresh: Bool, startAt: TimeInterval?) {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let url = try await self.resolve(request, forceFresh: forceFresh)
                try Task.checkCancellation()
                self.currentStreamURL = url
                if self.isCasting {
                    self.loadOnCast(url: url, startAt: startAt, autoplay: true)
                } else {
                    self.loadLocally(url: url, startAt: startAt, autoplay: true)
                }
            } catch is CancellationError {
            } catch {
                self.isLoading = false
                self.error = friendlyError(error)
            }
        }
    }

    /// Zoekt het stream-adres op. Een verbindingsfout krijgt één herkansing na
    /// een korte pauze; de HTTP-laag zelf probeert het al een paar keer.
    private func resolve(_ request: PlayRequest, forceFresh: Bool) async throws -> URL {
        do {
            return try await resolveOnce(request, forceFresh: forceFresh)
        } catch let error as URLError {
            _ = error
            try await Task.sleep(nanoseconds: 1_500_000_000)
            return try await resolveOnce(request, forceFresh: true)
        }
    }

    private func resolveOnce(_ request: PlayRequest, forceFresh: Bool) async throws -> URL {
        switch request {
        case let .episode(episode, _):
            let address = try await video.streamURL(episode.videoSlug, forceFresh: forceFresh)
            guard let url = URL(string: address) else {
                throw Family7Error("Geen afspeelbare videobron gevonden voor deze aflevering.")
            }
            return url
        case .live:
            let info = try await live.liveInfo()
            title = info.currentProgram.nonEmpty ?? "Family7 Live"
            subtitle = [info.timeRange, "Live"].filter { !$0.isEmpty }.joined(separator: " · ")
            if !info.imageURL.isEmpty { artworkURL = info.imageURL }
            guard let url = URL(string: info.streamURL), !info.streamURL.isEmpty else {
                throw Family7Error("De livestream van Family7 is nu niet te vinden.")
            }
            return url
        }
    }

    private func loadLocally(url: URL, startAt: TimeInterval?, autoplay: Bool) {
        let item = AVPlayerItem(url: url)
        // Een paar seconden voorraad: snel starten, maar bestand tegen een hapering.
        item.preferredForwardBufferDuration = isLive ? 0 : 20
        item.externalMetadata = nowPlayingMetadata()
        observe(item)
        player.replaceCurrentItem(with: item)
        if let startAt, !isLive, startAt > 0 {
            player.seek(to: CMTime(seconds: startAt, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .positiveInfinity)
        }
        if autoplay { player.play() } else { player.pause() }
        updateNowPlaying()
        loadArtwork()
    }

    private func loadOnCast(url: URL, startAt: TimeInterval?, autoplay: Bool) {
        guard let client = remoteClient else {
            loadLocally(url: url, startAt: startAt, autoplay: autoplay)
            return
        }
        let metadata = GCKMediaMetadata(metadataType: isLive ? .generic : .tvShow)
        metadata.setString(title, forKey: kGCKMetadataKeyTitle)
        metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle)
        if let artwork = URL(string: artworkURL), !artworkURL.isEmpty {
            metadata.addImage(GCKImage(url: artwork, width: 1280, height: 720))
        }

        let builder = GCKMediaInformationBuilder(contentURL: url)
        // Streampartner zet soms een mp4 achter een HLS-playlist; alleen het
        // einde van het pad telt.
        builder.contentType = Self.mimeType(for: url)
        // Live tv als live aanmelden: geen tijdbalk met eindpunt op de tv.
        builder.streamType = isLive ? .live : .buffered
        builder.metadata = metadata

        let options = GCKMediaLoadOptions()
        options.autoplay = autoplay
        if let startAt, !isLive { options.playPosition = startAt }
        client.loadMedia(builder.build(), with: options)
        isLoading = true
    }

    nonisolated static func mimeType(for url: URL) -> String {
        url.path.lowercased().hasSuffix(".mp4") ? "video/mp4" : "application/x-mpegURL"
    }

    // MARK: - herstel

    private func recover(reason: String?) {
        guard let request else { return }
        if recoveryAttempts < Self.maxRecoveryAttempts {
            recoveryAttempts += 1
            let attempt = recoveryAttempts
            let position = resumePosition
            isLoading = true
            error = nil
            loadTask?.cancel()
            loadTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                self.load(request, forceFresh: true, startAt: position)
            }
            return
        }
        isLoading = false
        isPlaying = false
        if isCasting {
            error = "Dit programma speelt niet af op \(castDeviceName ?? "de Chromecast"). " +
                "U kunt het opnieuw proberen of op deze telefoon verder kijken."
        } else {
            error = reason ?? "Afspelen lukt nu niet. Probeer het opnieuw."
        }
    }

    /// Wordt aangeroepen als het netwerk terug is.
    func networkRestored() {
        if error != nil, request != nil, !isLoading { retry() }
    }

    private var resumePosition: TimeInterval? {
        guard !isLive else { return nil }
        let seconds = isCasting ? castPosition : player.currentTime().seconds
        return seconds.isFinite && seconds > 0 ? seconds : nil
    }

    // MARK: - lokale speler volgen

    private func observePlayer() {
        observations.append(player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in
                guard let self, !self.isCasting else { return }
                self.isPlaying = status == .playing
                if status == .playing {
                    self.isLoading = false
                    // Weer aan het spelen: eerdere herkansingen tellen niet meer mee.
                    self.recoveryAttempts = 0
                }
                self.updateNowPlaying()
            }
        })
        observations.append(player.observe(\.isExternalPlaybackActive, options: [.initial, .new]) { [weak self] player, _ in
            let active = player.isExternalPlaybackActive
            Task { @MainActor in self?.isAirPlaying = active }
        })
    }

    private func observe(_ item: AVPlayerItem) {
        itemObservers.forEach(NotificationCenter.default.removeObserver)
        itemObservers = []
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let failed = item.status == .failed
            let message = item.error?.localizedDescription
            Task { @MainActor in
                guard let self, failed, self.player.currentItem === item else { return }
                self.recover(reason: message.map { "Afspeelfout: \($0)" })
            }
        }
        itemObservers.append(NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.recover(reason: nil) }
        })
    }

    private func reset() {
        request = nil
        title = ""
        subtitle = ""
        artworkURL = ""
        isLive = false
        isLoading = false
        isPlaying = false
        error = nil
        currentStreamURL = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - systeem: audio, vergrendelscherm, bedieningspaneel

    private func configureAudioSession() {
        // .playback: geluid ook met de stille modus aan, doorspelen op de
        // achtergrond, en de voorwaarde voor beeld-in-beeld en AirPlay.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in self?.player.play(); return .success }
        center.pauseCommand.addTarget { [weak self] _ in self?.player.pause(); return .success }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.togglePlayPause(); return .success }
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipBackwardCommand.addTarget { [weak self] _ in self?.skip(by: -10); return .success }
        center.skipForwardCommand.addTarget { [weak self] _ in self?.skip(by: 30); return .success }
    }

    private func updateNowPlaying() {
        guard request != nil, !isCasting else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: subtitle.isEmpty ? title : subtitle,
            MPMediaItemPropertyArtist: title,
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPNowPlayingInfoPropertyPlaybackRate: player.rate,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0
        ]
        if let duration = player.currentItem?.duration.seconds, duration.isFinite {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        if let data = artworkData, let image = UIImage(data: data) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Titel en omslag gaan mee met de stream, zodat AirPlay en het
    /// bedieningspaneel ze tonen.
    private func nowPlayingMetadata() -> [AVMetadataItem] {
        func item(_ identifier: AVMetadataIdentifier, _ value: NSCopying & NSObjectProtocol) -> AVMetadataItem {
            let metadata = AVMutableMetadataItem()
            metadata.identifier = identifier
            metadata.value = value
            metadata.extendedLanguageTag = "und"
            return metadata.copy() as! AVMetadataItem
        }
        var items = [item(.commonIdentifierTitle, (subtitle.isEmpty ? title : subtitle) as NSString),
                     item(.iTunesMetadataTrackSubTitle, title as NSString)]
        if let data = artworkData { items.append(item(.commonIdentifierArtwork, data as NSData)) }
        return items
    }

    private func loadArtwork() {
        guard let url = URL(string: artworkURL), !artworkURL.isEmpty else { return }
        Task { [weak self] in
            guard let data = await ImagePipeline.shared.data(for: url) else { return }
            guard let self else { return }
            self.artworkData = data
            self.player.currentItem?.externalMetadata = self.nowPlayingMetadata()
            self.updateNowPlaying()
        }
    }

    // MARK: - Google Cast

    private var remoteClient: GCKRemoteMediaClient? {
        GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient
    }

    private func attach(to session: GCKCastSession) {
        session.remoteMediaClient?.add(self)
        castDeviceName = session.device.friendlyName
        castVolume = session.currentDeviceVolume
        castMuted = session.currentDeviceMuted
        isCasting = true
        startCastTimer()
    }

    /// De lopende weergave naar de tv: op dezelfde plek, en daar meteen spelen.
    private func transferToCast() {
        let position = resumePosition
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let url = currentStreamURL {
            loadOnCast(url: url, startAt: position, autoplay: true)
        }
    }

    /// Terug naar de telefoon, gepauzeerd: geen onverwacht geluid uit een broekzak.
    private func transferToPhone(lastPosition: TimeInterval) {
        isCasting = false
        castDeviceName = nil
        stopCastTimer()
        guard request != nil, let url = currentStreamURL else { return }
        // Het casten stopt terwijl de kijker ergens anders in de app is: dan
        // niet stilletjes een speler op de achtergrond klaarzetten.
        guard playerScreenVisible else {
            player.replaceCurrentItem(with: nil)
            reset()
            return
        }
        isLoading = false
        loadLocally(url: url, startAt: isLive ? nil : lastPosition, autoplay: false)
    }

    private func startCastTimer() {
        stopCastTimer()
        castTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let client = self.remoteClient else { return }
                self.castPosition = client.approximateStreamPosition()
            }
        }
    }

    private func stopCastTimer() {
        castTimer?.invalidate()
        castTimer = nil
    }
}

// MARK: - sessies en status van de Chromecast

extension PlaybackManager: GCKSessionManagerListener, GCKRemoteMediaClientListener {

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        MainActor.assumeIsolated {
            attach(to: session)
            recoveryAttempts = 0
            error = nil
            transferToCast()
        }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        MainActor.assumeIsolated { attach(to: session) }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, willEnd session: GCKCastSession) {
        MainActor.assumeIsolated {
            castPosition = session.remoteMediaClient?.approximateStreamPosition() ?? castPosition
        }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        MainActor.assumeIsolated {
            session.remoteMediaClient?.remove(self)
            transferToPhone(lastPosition: castPosition)
        }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        MainActor.assumeIsolated {
            isCasting = false
            castDeviceName = nil
        }
    }

    /// Volume van de tv bijhouden, ook als het op de tv zelf verandert.
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, castSession session: GCKCastSession,
                                    didReceiveDeviceVolume volume: Float, muted: Bool) {
        MainActor.assumeIsolated {
            castVolume = volume
            castMuted = muted
        }
    }

    nonisolated func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        MainActor.assumeIsolated {
            guard let status = mediaStatus else { return }
            isPlaying = status.playerState == .playing
            if status.playerState == .playing || status.playerState == .paused {
                isLoading = false
                if status.playerState == .playing { recoveryAttempts = 0 }
            } else if status.playerState == .buffering || status.playerState == .loading {
                isLoading = true
            }
            if let duration = status.mediaInformation?.streamDuration, duration.isFinite, duration > 0 {
                castDuration = duration
            }
            if status.playerState == .idle && status.idleReason == .error {
                recover(reason: nil)
            }
        }
    }
}

/// Een foutmelding die een kijker begrijpt.
func friendlyError(_ error: Error) -> String {
    if let urlError = error as? URLError {
        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
            return "Geen verbinding met Family7. De app probeert het vanzelf opnieuw zodra er weer internet is."
        case .timedOut:
            return "Family7 reageert traag. Probeer het zo nog eens."
        case .secureConnectionFailed, .serverCertificateUntrusted:
            return "Er kon geen veilige verbinding met Family7 worden gemaakt."
        default:
            return "De verbinding met Family7 viel weg. Probeer het opnieuw."
        }
    }
    if error is UnauthorizedError { return "U bent niet (meer) ingelogd. Log opnieuw in." }
    return (error as? LocalizedError)?.errorDescription ?? "Er ging iets mis bij het laden."
}
