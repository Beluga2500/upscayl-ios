import SwiftUI

struct CompareView: View {
    let before: UIImage
    let after: UIImage
    @State private var split: CGFloat = 0.5
    @EnvironmentObject private var loc: Loc

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack(alignment: .leading) {
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
                    .fill(Color.white)
                    .frame(width: 2)
                    .overlay(
                        Circle().fill(Color.white).frame(width: 28, height: 28)
                            .shadow(radius: 3)
                            .overlay(Image(systemName: "arrow.left.and.right")
                                .font(.caption).foregroundColor(.black))
                    )
                    .offset(x: w * split - 1)

                HStack {
                    tag(loc.t("Avant", "Before"))
                    Spacer()
                    tag(loc.t("Après", "After"))
                }
                .padding(8)
                .frame(maxHeight: .infinity, alignment: .top)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { v in
                    split = min(max(v.location.x / w, 0), 1)
                }
            )
        }
    }

    private func tag(_ t: String) -> some View {
        Text(t).font(.caption2.bold())
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(.ultraThinMaterial, in: Capsule())
    }
}
