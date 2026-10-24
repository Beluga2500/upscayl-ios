import SwiftUI

struct CompareView: View {
    let before: UIImage
    let after: UIImage
    @State private var split: CGFloat = 0.5
    @EnvironmentObject private var loc: Loc

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack(alignment: .topLeading) {
                Image(uiImage: after)
                    .resizable().scaledToFit()
                    .frame(width: w, height: h)

                Image(uiImage: before)
                    .resizable().scaledToFit()
                    .frame(width: w, height: h)
                    .mask(alignment: .leading) {
                        Rectangle().frame(width: w * split)
                    }

                Rectangle()
                    .fill(Color.white.opacity(0.9))
                    .frame(width: 2, height: h)
                    .offset(x: w * split - 1)

                Circle()
                    .fill(.ultraThinMaterial)
                    .overlay(Circle().stroke(Color.white.opacity(0.55), lineWidth: 1))
                    .overlay(
                        Image(systemName: "arrow.left.and.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.primary)
                    )
                    .frame(width: 34, height: 34)
                    .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
                    .offset(x: w * split - 17, y: h / 2 - 17)

                // Zone de saisie : uniquement autour du séparateur, pour laisser le balayage entre onglets libre
                Color.clear
                    .frame(width: 48, height: h)
                    .contentShape(Rectangle())
                    .offset(x: w * split - 24)
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .named("cmp"))
                            .onChanged { v in split = min(max(v.location.x / w, 0), 1) }
                    )

                HStack {
                    tag(loc.t("Avant", "Before"))
                    Spacer()
                    tag(loc.t("Après", "After"))
                }
                .padding(12)
                .frame(width: w, height: h, alignment: .top)
                .allowsHitTesting(false)
            }
            .coordinateSpace(name: "cmp")
        }
    }

    private func tag(_ t: String) -> some View {
        Text(t).font(.caption2.weight(.semibold))
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(.ultraThinMaterial, in: Capsule())
    }
}
