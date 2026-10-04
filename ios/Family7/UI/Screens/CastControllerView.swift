import SwiftUI

/// De afstandsbediening tijdens het casten, in Family7-stijl (gelijk aan de
/// Android-app): omslag, tijdbalk of LIVE met "Naar live", afspelen, spoelen,
/// vorige en volgende aflevering, het volume van de tv, en terug naar de
/// telefoon. Speelt er nog niets, dan is dit het "klaar om te casten"-scherm.
struct CastControllerView: View {
    @Environment(AppModel.self) private var model
    let onClose: () -> Void

    var body: some View {
        let playback = model.playback
        ZStack {
            Color.family7Background.ignoresSafeArea()
            if !playback.artworkURL.isEmpty {
                RemoteImage(url: playback.artworkURL)
                    .blur(radius: 40)
                    .opacity(0.45)
                    .ignoresSafeArea()
            }
            LinearGradient(stops: [.init(color: Color.family7Blue.opacity(0.55), location: 0),
                                   .init(color: Color.family7Background.opacity(0.9), location: 0.55),
                                   .init(color: Color.family7Background, location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    HStack {
                        Button(action: onClose) {
                            Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Sluiten")
                        Spacer()
                        Label(playback.castDeviceName ?? "Chromecast", systemImage: "tv")
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(.white.opacity(0.1), in: Capsule())
                        Spacer()
                        TVButtons()
                    }
                    .padding(.horizontal, 8)

                    if playback.request == nil {
                        ReadyToCastView(device: playback.castDeviceName, onChoose: onClose, onStop: playback.stopCasting)
                    } else {
                        NowPlayingRemote()
                    }
                }
            }
        }
        .foregroundStyle(.white)
    }
}

/// Verbonden met de tv, maar er speelt nog niets.
private struct ReadyToCastView: View {
    let device: String?
    let onChoose: () -> Void
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image("Family7Mark").resizable().scaledToFit().frame(width: 140)
                .padding(.bottom, 16)
            Text("Klaar om te casten").font(.title2.weight(.bold))
            Text("Verbonden met \(device ?? "uw tv"). Kies een programma of live tv; het speelt meteen op de tv.")
                .font(.subheadline)
                .foregroundStyle(Color.family7Secondary)
                .multilineTextAlignment(.center)
            Button("Programma kiezen", action: onChoose)
                .buttonStyle(.borderedProminent).tint(.family7Red)
                .padding(.top, 16)
            Button("Verbinding met de tv verbreken", action: onStop)
                .font(.footnote)
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 48)
    }
}

private struct NowPlayingRemote: View {
    @Environment(AppModel.self) private var model
    @State private var scrubbing: Double?
    @State private var volume: Float?

    var body: some View {
        let playback = model.playback
        VStack(spacing: 16) {
            ZStack(alignment: .topLeading) {
                RemoteImage(url: playback.artworkURL)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .overlay {
                        if playback.isLoading { ZStack { Color.black.opacity(0.4); ProgressView().tint(.white) } }
                    }
                if playback.isLive {
                    Text("LIVE").font(.caption.weight(.bold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.family7Red, in: RoundedRectangle(cornerRadius: 4))
                        .padding(10)
                }
            }
            .frame(maxWidth: 520)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .shadow(color: .black.opacity(0.5), radius: 16, y: 8)
            .padding(.top, 16)

            VStack(spacing: 4) {
                Text(playback.title).font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(2)
                if !playback.subtitle.isEmpty {
                    Text(playback.subtitle).font(.subheadline).foregroundStyle(Color.family7Secondary)
                        .multilineTextAlignment(.center).lineLimit(2)
                }
            }

            if playback.isLive {
                HStack(spacing: 8) {
                    Circle().fill(Color.family7Red).frame(width: 8, height: 8)
                    Text("Live uitzending").font(.subheadline).foregroundStyle(Color.family7Secondary)
                    Button("Naar live", action: playback.goLive).font(.subheadline.weight(.semibold))
                }
            } else {
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { scrubbing ?? playback.castPosition },
                                          set: { scrubbing = $0 }),
                           in: 0...max(playback.castDuration, 1)) { editing in
                        if !editing, let target = scrubbing {
                            playback.seek(to: target)
                            scrubbing = nil
                        }
                    }
                    .tint(.family7Red)
                    .disabled(playback.castDuration <= 0)
                    .accessibilityLabel("Tijdbalk")
                    HStack {
                        Text(timeText(scrubbing ?? playback.castPosition))
                        Spacer()
                        Text(playback.castDuration > 0 ? timeText(playback.castDuration) : "--:--")
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.family7Secondary)
                }
                .frame(maxWidth: 560)
            }

            HStack(spacing: 22) {
                Button(action: playback.playPrevious) { Image(systemName: "backward.end.fill").font(.title2) }
                    .disabled(!playback.hasPrevious).accessibilityLabel("Vorige aflevering")
                Button { playback.skip(by: -10) } label: { Image(systemName: "gobackward.10").font(.title) }
                    .disabled(playback.isLive).accessibilityLabel("10 seconden terug")
                Button(action: playback.togglePlayPause) {
                    Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 34))
                        .frame(width: 76, height: 76)
                        .background(Color.family7Red, in: Circle())
                }
                .accessibilityLabel(playback.isPlaying ? "Pauzeren" : "Afspelen")
                Button { playback.skip(by: 30) } label: { Image(systemName: "goforward.30").font(.title) }
                    .disabled(playback.isLive).accessibilityLabel("30 seconden vooruit")
                Button(action: playback.playNext) { Image(systemName: "forward.end.fill").font(.title2) }
                    .disabled(!playback.hasNext).accessibilityLabel("Volgende aflevering")
            }
            .foregroundStyle(.white)
            .padding(.vertical, 4)

            HStack(spacing: 10) {
                Button(action: playback.toggleCastMute) {
                    Image(systemName: playback.castMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .foregroundStyle(Color.family7Secondary)
                }
                .accessibilityLabel(playback.castMuted ? "Geluid van de tv aan" : "Geluid van de tv uit")
                Slider(value: Binding(get: { volume ?? playback.castVolume },
                                      set: { volume = $0; playback.setCastVolume($0) }),
                       in: 0...1) { editing in if !editing { volume = nil } }
                    .tint(.white)
                    .accessibilityLabel("Volume van de tv")
            }
            .frame(maxWidth: 560)

            Button(action: playback.stopCasting) {
                Label("Op telefoon kijken", systemImage: "iphone")
            }
            .buttonStyle(.bordered)
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
    }

    private func timeText(_ seconds: Double) -> String {
        let total = Int(seconds.isFinite ? seconds : 0)
        return total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
    }
}
