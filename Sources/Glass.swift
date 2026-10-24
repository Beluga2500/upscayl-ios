import SwiftUI

enum Theme {
    static let accent = Color(red: 0.04, green: 0.52, blue: 1.0)
}

/// Fond statique : deux halos très doux, aucun calcul par frame.
struct GlassBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            Color(.systemBackground)
            RadialGradient(
                colors: [Theme.accent.opacity(scheme == .dark ? 0.34 : 0.20), .clear],
                center: .topLeading, startRadius: 0, endRadius: 430)
            RadialGradient(
                colors: [Color(red: 0.35, green: 0.78, blue: 0.98).opacity(scheme == .dark ? 0.20 : 0.16), .clear],
                center: .bottomTrailing, startRadius: 0, endRadius: 470)
        }
        .ignoresSafeArea()
    }
}

extension View {
    /// Vrai Liquid Glass sur iOS 26+ (si compilé avec Xcode 26), sinon matériau translucide.
    @ViewBuilder
    func glass<S: Shape>(_ shape: S, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            if interactive {
                self.glassEffect(.regular.interactive(), in: shape)
            } else {
                self.glassEffect(.regular, in: shape)
            }
        } else {
            self.fallbackGlass(shape)
        }
        #else
        self.fallbackGlass(shape)
        #endif
    }

    func fallbackGlass<S: Shape>(_ shape: S) -> some View {
        self
            .background(.ultraThinMaterial, in: shape)
            .overlay(shape.stroke(Color.white.opacity(0.18), lineWidth: 0.8))
    }
}

/// Pastille de sélection de la barre d'onglets.
struct GlassPill: View {
    var lifted: Bool

    var body: some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            Capsule().fill(Color.clear).glassEffect(.regular.interactive(), in: Capsule())
        } else {
            fallback
        }
        #else
        fallback
        #endif
    }

    private var fallback: some View {
        Capsule()
            .fill(Color.primary.opacity(0.11))
            .overlay(Capsule().stroke(Color.white.opacity(0.30), lineWidth: 0.8))
            .shadow(color: .black.opacity(lifted ? 0.20 : 0.08), radius: lifted ? 12 : 4, y: 3)
    }
}

struct GlassButtonStyle: ButtonStyle {
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        let label = configuration.label
            .font(.body.weight(.semibold))
            .padding(.vertical, 16)
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity)
            .opacity(configuration.isPressed ? 0.75 : 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)

        if prominent {
            label
                .foregroundColor(.white)
                .background(
                    Capsule()
                        .fill(Theme.accent)
                        .overlay(
                            Capsule().fill(
                                LinearGradient(colors: [Color.white.opacity(0.22), .clear],
                                               startPoint: .top, endPoint: .center))
                        )
                        .shadow(color: Theme.accent.opacity(0.35), radius: 12, y: 5)
                )
        } else {
            label
                .foregroundColor(.primary)
                .glass(Capsule(), interactive: true)
        }
    }
}

struct GlassCircleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(.primary)
            .opacity(configuration.isPressed ? 0.75 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .glass(Circle(), interactive: true)
    }
}
