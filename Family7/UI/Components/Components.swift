import SwiftUI

/// Een glanzende plaatshouder in de vorm van de inhoud die eraan komt.
struct ShimmerView: View {
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { geometry in
            Color.family7Surface
                .overlay(
                    LinearGradient(colors: [.clear, .white.opacity(0.08), .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: geometry.size.width * 0.6)
                        .offset(x: phase * geometry.size.width)
                )
        }
        .clipped()
        .onAppear {
            withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) { phase = 1.4 }
        }
        .accessibilityHidden(true)
    }
}

struct ProgramCard: View {
    let program: ProgramItem
    var width: CGFloat? = 168

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RemoteImage(url: program.thumbnailURL)
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .topLeading) {
                    if !program.badge.isEmpty {
                        Text(program.badge)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.family7Red, in: RoundedRectangle(cornerRadius: 4))
                            .padding(6)
                    }
                }
            Text(program.title)
                .font(.subheadline)
                .lineLimit(2, reservesSpace: true)
                .multilineTextAlignment(.leading)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Rustige melding boven inhoud die er al staat: offline, of verversen mislukt.
struct StatusBanner: View {
    let isOffline: Bool
    let error: String?
    var onRetry: (() -> Void)?

    var body: some View {
        if let text = isOffline ? "Geen internet. U ziet wat er het laatst geladen is." : error {
            HStack(spacing: 10) {
                Image(systemName: isOffline ? "wifi.slash" : "exclamationmark.icloud")
                Text(text).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                if !isOffline, let onRetry {
                    Button("Opnieuw", action: onRetry).font(.footnote.weight(.semibold))
                }
            }
            .foregroundStyle(Color.family7Secondary)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(Color.family7Surface)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

/// Foutscherm voor als er niets te tonen is.
struct FullScreenError: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundStyle(Color.family7Secondary)
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(Color.family7Secondary)
            Button("Opnieuw proberen", action: onRetry)
                .buttonStyle(.borderedProminent)
                .tint(.family7Red)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct SkeletonRow: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ShimmerView().frame(width: 140, height: 18).clipShape(RoundedRectangle(cornerRadius: 4))
            HStack(spacing: 12) {
                ForEach(0..<3, id: \.self) { _ in
                    ShimmerView().frame(width: 168, height: 94).clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

/// Een raster met programma's, met plaatshouders tijdens het laden.
struct ProgramGrid: View {
    let programs: [ProgramItem]?
    let emptyMessage: String

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 12)]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
            if let programs {
                ForEach(programs) { program in
                    NavigationLink(value: program) { ProgramCard(program: program, width: nil) }
                        .buttonStyle(.plain)
                }
            } else {
                ForEach(0..<8, id: \.self) { _ in
                    ShimmerView().aspectRatio(16 / 9, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
        }
        .padding(16)
        .overlay {
            if let programs, programs.isEmpty {
                Text(emptyMessage)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.family7Secondary)
                    .padding(32)
            }
        }
    }
}
