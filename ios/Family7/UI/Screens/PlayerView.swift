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

            if playback.isCasting {
                // Tijdens het casten: de afstandsbediening (of "klaar om te casten").
                CastControllerView(onClose: { dismiss() })
            } else if landscape {
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
        // Niets meer om af te spelen: sluiten, behalve als de tv verbonden is;
        // dan blijft het scherm als "klaar om te casten".
        .onChange(of: playback.request == nil && !playback.isCasting) { _, ended in
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
        ZStack {
            VideoPlayerController(player: playback.player) { model.showPlayer = true }
            if playback.isLoading && playback.player.currentItem == nil {
                ProgressView().controlSize(.large).tint(.white)
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

        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
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
