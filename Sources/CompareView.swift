import SwiftUI

/// Comparaison avant / après : séparateur déplaçable, zoom au pincement, déplacement au doigt, double toucher.
struct CompareView: View {
    let before: UIImage
    let after: UIImage

    @State private var split: CGFloat = 0.5
    @State private var zoom: CGFloat = 1
    @State private var lastZoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var lastPan: CGSize = .zero
    @EnvironmentObject private var loc: Loc

    private let maxZoom: CGFloat = 10

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack(alignment: .topLeading) {
                layer(after, w, h)
                layer(before, w, h)
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
                    .allowsHitTesting(false)

                // Zone de saisie du séparateur (prioritaire sur le déplacement de l'image)
                Color.clear
                    .frame(width: 52, height: h)
                    .contentShape(Rectangle())
                    .offset(x: w * split - 26)
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
            .frame(width: w, height: h)
            .clipped()
            .overlay(alignment: .bottomLeading) {
                if zoom > 1.01 {
                    Button {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { resetZoom() }
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "minus.magnifyingglass")
                            Text(String(format: "×%.1f", Double(zoom)))
                        }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 11).padding(.vertical, 6)
                        .background(.ultraThinMaterial, in: Capsule())
                        .foregroundColor(.primary)
                    }
                    .padding(12)
                }
            }
            .contentShape(Rectangle())
            .gesture(panGesture(w, h))
            .simultaneousGesture(magnifyGesture(w, h))
            .onTapGesture(count: 2) {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                    if zoom > 1.01 { resetZoom() } else { zoom = 3; lastZoom = 3 }
                }
            }
        }
    }

    private func layer(_ img: UIImage, _ w: CGFloat, _ h: CGFloat) -> some View {
        Image(uiImage: img)
            .resizable().scaledToFit()
            .frame(width: w, height: h)
            .scaleEffect(zoom)
            .offset(pan)
    }

    private func resetZoom() {
        zoom = 1; lastZoom = 1
        pan = .zero; lastPan = .zero
    }

    private func clamped(_ s: CGSize, _ w: CGFloat, _ h: CGFloat) -> CGSize {
        let mx = (zoom - 1) * w / 2, my = (zoom - 1) * h / 2
        return CGSize(width: min(max(s.width, -mx), mx), height: min(max(s.height, -my), my))
    }

    private func panGesture(_ w: CGFloat, _ h: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { v in
                guard zoom > 1.01 else { return }
                pan = clamped(CGSize(width: lastPan.width + v.translation.width,
                                     height: lastPan.height + v.translation.height), w, h)
            }
            .onEnded { _ in lastPan = pan }
    }

    private func magnifyGesture(_ w: CGFloat, _ h: CGFloat) -> some Gesture {
        MagnificationGesture()
            .onChanged { v in
                zoom = min(max(lastZoom * v, 1), maxZoom)
                pan = clamped(pan, w, h)
            }
            .onEnded { _ in
                lastZoom = zoom
                if zoom <= 1.01 { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { resetZoom() } }
                else { lastPan = pan }
            }
    }

    private func tag(_ t: String) -> some View {
        Text(t).font(.caption2.weight(.semibold))
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(.ultraThinMaterial, in: Capsule())
    }
}
