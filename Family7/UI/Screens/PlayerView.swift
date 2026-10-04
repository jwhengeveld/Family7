import AVKit
import SwiftUI

/// Het spelerscherm.
///
/// Rechtop: een balk met sluiten, AirPlay en Cast, de video, en daaronder wat
/// er speelt. Liggend vult de video het scherm. Tijdens het casten is dit een
/// afstandsbediening voor de tv.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        let playback = model.playback
        let landscape = verticalSizeClass == .compact && !playback.isCasting

        ZStack {
            Color.black.ignoresSafeArea()

            if landscape {
                videoArea.ignoresSafeArea()
            } else {
                VStack(spacing: 0) {
                    topBar
                    videoArea.aspectRatio(16 / 9, contentMode: .fit)
                    info
                    Spacer(minLength: 0)
                }
            }

            if let error = playback.error {
                ErrorOverlay(message: error, isCasting: playback.isCasting,
                             onRetry: playback.retry,
                             onPhone: playback.stopCasting,
                             onClose: { dismiss() })
            }
        }
        .statusBarHidden(landscape)
        .persistentSystemOverlays(landscape ? .hidden : .automatic)
        .onAppear { playback.playerScreenVisible = true }
        .onDisappear {
            playback.playerScreenVisible = false
            // Het scherm sluiten stopt de weergave op de telefoon, behalve als
            // hij in beeld-in-beeld verder speelt; een cast-sessie loopt door.
            if !PictureInPictureState.shared.isActive { playback.stopLocal() }
        }
        .onChange(of: playback.request == nil) { _, ended in
            if ended { dismiss() }
        }
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Sluiten")
            Spacer()
            TVButtons()
        }
        .padding(.horizontal, 8)
    }

    @ViewBuilder
    private var videoArea: some View {
        let playback = model.playback
        if playback.isCasting {
            CastingArtwork()
        } else {
            ZStack {
                VideoPlayerController(player: playback.player) { model.showPlayer = true }
                if playback.isLoading && playback.player.currentItem == nil {
                    ProgressView().controlSize(.large).tint(.white)
                }
            }
        }
    }

    private var info: some View {
        let playback = model.playback
        return VStack(alignment: .leading, spacing: 6) {
            if playback.isLive {
                Label("LIVE", systemImage: "circle.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.family7Red)
                    .labelStyle(.titleAndIcon)
            }
            Text(playback.title).font(.title3.weight(.bold))
            if !playback.subtitle.isEmpty {
                Text(playback.subtitle).font(.subheadline).foregroundStyle(Color.family7Secondary)
            }
            if playback.isAirPlaying {
                Label("Speelt af via AirPlay", systemImage: "airplayvideo")
                    .font(.footnote).foregroundStyle(Color.family7Secondary).padding(.top, 4)
            }
            if playback.isCasting {
                CastRemote().padding(.top, 16)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
    }
}

/// Tijdens het casten: de omslag en waar het speelt.
private struct CastingArtwork: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let playback = model.playback
        ZStack {
            RemoteImage(url: playback.artworkURL)
            Color.black.opacity(0.6)
            VStack(spacing: 10) {
                Image(systemName: "tv.and.mediabox").font(.system(size: 40)).foregroundStyle(Color.family7Red)
                Text("Speelt af op \(playback.castDeviceName ?? "uw tv")").font(.subheadline)
                if playback.isLoading { ProgressView().tint(.white) }
            }
        }
    }
}

/// De afstandsbediening voor de Chromecast.
private struct CastRemote: View {
    @Environment(AppModel.self) private var model
    @State private var scrubbing: Double?

    var body: some View {
        let playback = model.playback
        VStack(spacing: 16) {
            if !playback.isLive && playback.castDuration > 0 {
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { scrubbing ?? playback.castPosition },
                                          set: { scrubbing = $0 }),
                           in: 0...playback.castDuration) { editing in
                        if !editing, let target = scrubbing {
                            playback.seek(to: target)
                            scrubbing = nil
                        }
                    }
                    HStack {
                        Text(timeText(scrubbing ?? playback.castPosition))
                        Spacer()
                        Text(timeText(playback.castDuration))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.family7Secondary)
                }
            }
            HStack(spacing: 40) {
                if !playback.isLive {
                    Button { playback.skip(by: -10) } label: { Image(systemName: "gobackward.10") }
                        .accessibilityLabel("10 seconden terug")
                }
                Button { playback.togglePlayPause() } label: {
                    Image(systemName: playback.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 56))
                }
                .accessibilityLabel(playback.isPlaying ? "Pauzeren" : "Afspelen")
                if !playback.isLive {
                    Button { playback.skip(by: 30) } label: { Image(systemName: "goforward.30") }
                        .accessibilityLabel("30 seconden vooruit")
                }
            }
            .font(.title)
            .foregroundStyle(.white)
            Button("Op deze telefoon verder kijken", systemImage: "iphone") { playback.stopCasting() }
                .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity)
    }

    private func timeText(_ seconds: Double) -> String {
        let total = Int(seconds.isFinite ? seconds : 0)
        return total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
    }
}

private struct ErrorOverlay: View {
    let message: String
    let isCasting: Bool
    let onRetry: () -> Void
    let onPhone: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text(message).multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button("Opnieuw proberen", action: onRetry).buttonStyle(.borderedProminent).tint(.family7Red)
                if isCasting {
                    Button("Op telefoon", action: onPhone).buttonStyle(.bordered)
                } else {
                    Button("Sluiten", action: onClose).buttonStyle(.bordered)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.85).ignoresSafeArea())
    }
}

/// Houdt bij of beeld-in-beeld loopt, zodat het sluiten van het spelerscherm
/// de video in het venster niet stopt.
@MainActor
final class PictureInPictureState {
    static let shared = PictureInPictureState()
    var isActive = false
}

/// AVPlayerViewController: de bediening van iOS zelf, met AirPlay,
/// beeld-in-beeld (ook automatisch bij het verlaten van de app) en schermvullend.
private struct VideoPlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    /// Terug naar de app vanuit beeld-in-beeld: het spelerscherm weer tonen.
    let onRestore: () -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        // De app zet zelf titel en omslag in het bedieningspaneel.
        controller.updatesNowPlayingInfoCenter = false
        controller.videoGravity = .resizeAspect
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
        context.coordinator.onRestore = onRestore
    }

    func makeCoordinator() -> Coordinator { Coordinator(onRestore: onRestore) }

    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        var onRestore: () -> Void
        init(onRestore: @escaping () -> Void) { self.onRestore = onRestore }

        func playerViewControllerWillStartPictureInPicture(_ controller: AVPlayerViewController) {
            MainActor.assumeIsolated { PictureInPictureState.shared.isActive = true }
        }

        func playerViewControllerDidStopPictureInPicture(_ controller: AVPlayerViewController) {
            MainActor.assumeIsolated { PictureInPictureState.shared.isActive = false }
        }

        func playerViewController(_ controller: AVPlayerViewController,
                                  restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completion: @escaping (Bool) -> Void) {
            MainActor.assumeIsolated { onRestore() }
            completion(true)
        }
    }
}
