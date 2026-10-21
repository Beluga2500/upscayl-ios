import SwiftUI

/// Fond statique (aucun calcul à chaque frame).
struct GlassBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                Color(red: 0.36, green: 0.56, blue: 0.96).opacity(0.38),
                Color(red: 0.58, green: 0.38, blue: 0.92).opacity(0.22),
                Color(.systemBackground)
            ],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

extension View {
    /// Vrai Liquid Glass sur iOS 26+ (si compilé avec Xcode 26), sinon matériau translucide léger.
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
            .overlay(shape.stroke(Color.white.opacity(0.2), lineWidth: 0.8))
    }
}

struct GlassButtonStyle: ButtonStyle {
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        let label = configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.vertical, 13)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .opacity(configuration.isPressed ? 0.7 : 1)

        if prominent {
            label
                .foregroundColor(.white)
                .background(
                    Capsule().fill(
                        LinearGradient(
                            colors: [Color(red: 0.30, green: 0.52, blue: 0.98),
                                     Color(red: 0.50, green: 0.36, blue: 0.95)],
                            startPoint: .leading, endPoint: .trailing)
                    )
                )
        } else {
            label
                .foregroundColor(.primary)
                .glass(Capsule(), interactive: true)
        }
    }
}
