import SwiftUI

/// Barre d'onglets en verre : la pastille suit le doigt et se range sur l'onglet le plus proche.
struct GlassTabBar: View {
    let items: [(icon: String, title: String)]
    let progress: CGFloat          // 0 ... items.count - 1, continu
    let lifted: Bool               // true pendant le glissement
    var onScrub: (CGFloat) -> Void
    var onSettle: (Int) -> Void

    @Environment(\.theme) private var theme

    init(items: [(icon: String, title: String)],
         progress: CGFloat,
         lifted: Bool,
         onScrub: @escaping (CGFloat) -> Void,
         onSettle: @escaping (Int) -> Void) {
        self.items = items
        self.progress = progress
        self.lifted = lifted
        self.onScrub = onScrub
        self.onSettle = onSettle
    }

    private let pad: CGFloat = 5
    private let height: CGFloat = 62

    var body: some View {
        GeometryReader { geo in
            let itemW = (geo.size.width - pad * 2) / CGFloat(items.count)

            ZStack(alignment: .leading) {
                GlassPill(lifted: lifted)
                    .frame(width: itemW, height: height - pad * 2)
                    .scaleEffect(lifted ? 1.07 : 1)
                    .animation(.spring(response: 0.3, dampingFraction: 0.75), value: lifted)
                    .offset(x: pad + progress * itemW)

                HStack(spacing: 0) {
                    ForEach(items.indices, id: \.self) { i in
                        VStack(spacing: 3) {
                            Image(systemName: items[i].icon)
                                .font(.system(size: 20, weight: .medium))
                            Text(items[i].title)
                                .font(.system(size: 11, weight: .medium))
                        }
                        .foregroundColor(abs(progress - CGFloat(i)) < 0.5 ? theme.accent : .secondary)
                        .frame(width: itemW, height: height)
                    }
                }
                .padding(.horizontal, pad)
            }
            .frame(height: height)
            .glass(Capsule())
            .contentShape(Capsule())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in onScrub(value(at: v.location.x, itemW: itemW)) }
                    .onEnded { v in
                        let p = value(at: v.predictedEndLocation.x, itemW: itemW)
                        onSettle(Int(p.rounded()))
                    }
            )
        }
        .frame(height: height)
    }

    private func value(at x: CGFloat, itemW: CGFloat) -> CGFloat {
        let p = (x - pad - itemW / 2) / itemW
        return min(max(p, 0), CGFloat(items.count - 1))
    }
}
