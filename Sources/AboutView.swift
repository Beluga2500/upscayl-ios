import SwiftUI

struct AboutView: View {
    private let card = RoundedRectangle(cornerRadius: 24, style: .continuous)

    var body: some View {
        NavigationStack {
            ZStack {
                GlassBackground()
                ScrollView {
                    VStack(spacing: 14) {
                        header
                        forkCard
                        creditsCard
                        licenseCard
                        Text("Projet non officiel, ni affilié ni approuvé par Upscayl.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    }
                    .padding(16)
                }
            }
            .navigationTitle("À propos")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var header: some View {
        VStack(spacing: 10) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 84, height: 84)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Text("Upscayl iOS").font(.title2.bold())
            Text("Fork non officiel").font(.subheadline).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .glass(card)
    }

    private var forkCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Ce fork").font(.headline)
            Text("Cette version iOS a été réalisée par **Akuu**.")
            Text("Akuu ne détient **aucun droit** sur Upscayl, ses modèles, son logo ni son nom. Tout appartient à leurs créateurs et ayants droit listés ci-dessous.")
                .foregroundColor(.secondary)
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private var creditsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Appartient à").font(.headline)
            row("Upscayl", "© 2023 Nayam Amarshe & TGS963 et contributeurs")
            row("Logo", "NicKoehler")
            row("Real-ESRGAN (modèles)", "© 2021 Xintao Wang et al.")
            row("Contributeurs Upscayl", "JanDeDinoMan, xanderfrangos, Fdawgs, keturn, aaronliu0130")
            row("Core ML, SwiftUI, iOS", "Apple Inc.")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private var licenseCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Licences et code source").font(.headline)
            Text("Logiciel libre sous licence AGPL-3.0 (Upscayl). Real-ESRGAN : BSD-3-Clause.")
                .font(.subheadline).foregroundColor(.secondary)
            link("Upscayl (projet original)", "https://github.com/upscayl/upscayl")
            link("Real-ESRGAN", "https://github.com/xinntao/Real-ESRGAN")
            link("Code source de ce fork", "https://github.com/Beluga2500/upscayl-ios")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private func row(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(detail).font(.footnote).foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private func link(_ title: String, _ url: String) -> some View {
        if let u = URL(string: url) {
            Link(destination: u) {
                Label(title, systemImage: "arrow.up.right.square").font(.subheadline)
            }
        }
    }
}
