import SwiftUI

/// Het geanimeerde splashscherm, gelijk aan dat van de Android-apps.
///
/// Begint waar het opstartscherm van iOS ophoudt (het embleem van 165 pt op
/// effen blauw) en onthult dan het merk: het embleem schuift omhoog met een
/// zachte gloed erachter, en "Family7" met de ondertitel verschijnt eronder.
/// De app laadt intussen gewoon door; pas als `ready` waar is én de onthulling
/// klaar is, vervaagt het scherm.
struct SplashView: View {
    let ready: Bool
    let onFinished: () -> Void

    @State private var lift: CGFloat = 0
    @State private var glow: CGFloat = 0
    @State private var text: CGFloat = 0
    @State private var subtitle: CGFloat = 0
    @State private var waiting: CGFloat = 0
    @State private var exit: CGFloat = 1
    @State private var pulse = false

    var body: some View {
        ZStack {
            Color.family7Background
            RadialGradient(colors: [Color(red: 0x0A / 255, green: 0x3F / 255, blue: 0x86 / 255), .family7Background,
                                    Color(red: 0x02 / 255, green: 0x0D / 255, blue: 0x24 / 255)],
                           center: .center, startRadius: 0, endRadius: 520)
                .opacity(glow)

            Circle()
                .fill(RadialGradient(colors: [Color(red: 0.3, green: 0.64, blue: 1).opacity(0.4), .clear],
                                     center: .center, startRadius: 0, endRadius: 132))
                .frame(width: 264, height: 264)
                .scaleEffect(0.8 + 0.2 * glow)
                .opacity(0.55 * glow)
                .offset(y: -58 * lift)

            Image("Family7Mark")
                .resizable()
                .scaledToFit()
                .frame(width: 165)
                .scaleEffect(1 - 0.2 * lift)
                .offset(y: -58 * lift)
                .accessibilityLabel("Family7")

            Image("Family7Text")
                .resizable()
                .scaledToFit()
                .frame(width: 150)
                .offset(y: 62 + 14 * (1 - text))
                .opacity(text)
                .accessibilityHidden(true)

            Text("De christelijke familiezender")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Color.family7Secondary)
                .offset(y: 106 + 10 * (1 - subtitle))
                .opacity(subtitle)

            HStack(spacing: 8) {
                ForEach(Array([Color.family7Red, Color(red: 0.21, green: 0.78, blue: 0.08), Color(red: 0.11, green: 0.25, blue: 0.88)].enumerated()), id: \.offset) { index, color in
                    Circle()
                        .fill(color)
                        .frame(width: 8, height: 8)
                        .scaleEffect(pulse ? 1 : 0.7)
                        .opacity(pulse ? 1 : 0.35)
                        .animation(.easeInOut(duration: 0.52).repeatForever().delay(Double(index) * 0.16), value: pulse)
                }
            }
            .offset(y: 160)
            .opacity(waiting)
        }
        .ignoresSafeArea()
        .opacity(exit)
        .task { await run() }
    }

    private func run() async {
        try? await Task.sleep(nanoseconds: 120_000_000)
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.65)) { lift = 1 }
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.7).delay(0.08)) { glow = 1 }
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.52).delay(0.32)) { text = 1 }
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.5).delay(0.52)) { subtitle = 1 }
        try? await Task.sleep(nanoseconds: 1_020_000_000)

        // Nog niet klaar met laden: na een korte pauze de puntjes tonen.
        var waited = 0
        while !ready {
            if waited == 10 {
                pulse = true
                withAnimation(.easeIn(duration: 0.4)) { waiting = 1 }
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
            waited += 1
        }
        withAnimation(.linear(duration: 0.32)) { exit = 0 }
        try? await Task.sleep(nanoseconds: 330_000_000)
        onFinished()
    }
}
