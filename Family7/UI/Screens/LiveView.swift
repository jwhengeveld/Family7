import SwiftUI

struct LiveView: View {
    @Environment(AppModel.self) private var model
    @State private var info: LiveStreamInfo?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ZStack {
                    if let image = info?.imageURL.nonEmpty {
                        RemoteImage(url: image)
                    } else {
                        Color.family7Surface.overlay(Image("Family7Mark").resizable().scaledToFit().frame(width: 80))
                    }
                    Image(systemName: "play.circle.fill").font(.system(size: 64)).foregroundStyle(.white, Color.family7Red)
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxHeight: 360)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .onTapGesture { model.play(.live) }
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("Live kijken")

                HStack(spacing: 6) {
                    Circle().fill(Color.family7Red).frame(width: 8, height: 8)
                    Text("NU LIVE").font(.caption.weight(.bold)).foregroundStyle(Color.family7Red)
                    if let time = info?.timeRange.nonEmpty { Text(time).font(.caption).foregroundStyle(Color.family7Secondary) }
                }
                Text(info?.currentProgram ?? "Family7 Live").font(.title2.weight(.bold))
                if let description = info?.description.nonEmpty {
                    Text(description).foregroundStyle(Color.family7Secondary)
                }
                Button {
                    model.play(.live)
                } label: {
                    Label(model.playback.isCasting ? "Live op \(model.playback.castDeviceName ?? "de tv")" : "Live kijken",
                          systemImage: "play.fill")
                        .frame(maxWidth: .infinity, minHeight: 32)
                }
                .buttonStyle(.borderedProminent)
                .tint(.family7Red)
                if let error { Text(error).font(.footnote).foregroundStyle(Color.family7Secondary) }
            }
            .padding(16)
        }
        .background(Color.family7Background)
        .navigationTitle("Live")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { TVButtons() } }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        do {
            info = try await model.live.liveInfo()
            error = nil
        } catch {
            if info == nil { self.error = friendlyError(error) }
        }
    }
}
