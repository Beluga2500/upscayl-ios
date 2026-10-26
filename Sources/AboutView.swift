import SwiftUI

struct AboutView: View {
    @EnvironmentObject private var loc: Loc
    @ObservedObject private var updater = Updater.shared
    private let card = RoundedRectangle(cornerRadius: 26, style: .continuous)

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 14) {
                Text(loc.t("À propos", "About"))
                    .font(.largeTitle.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                header
                updateCard
                forkCard
                creditsCard
                licenseCard
                Text(loc.t("Projet non officiel, ni affilié ni approuvé par Upscayl.",
                           "Unofficial project, not affiliated with or endorsed by Upscayl."))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 4)
                    .padding(.bottom, 12)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
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
            Text(loc.t("Fork non officiel", "Unofficial fork")).font(.subheadline).foregroundColor(.secondary)
            Text("\(loc.t("Version", "Version")) \(AppInfo.version)")
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(.ultraThinMaterial, in: Capsule())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .glass(card)
    }

    private var updateStatus: String {
        if updater.checking { return loc.t("Vérification…", "Checking…") }
        if updater.failed { return loc.t("Impossible de vérifier (hors ligne ?).", "Could not check (offline?).") }
        if updater.available, let v = updater.remoteVersion {
            return loc.t("Version \(v) disponible.", "Version \(v) is available.")
        }
        if updater.remoteVersion != nil { return loc.t("Tu as la dernière version.", "You have the latest version.") }
        return "\(loc.t("Version installée", "Installed version")) : \(AppInfo.version)"
    }

    private var updateCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(loc.t("Mises à jour", "Updates")).font(.headline)
            Text(updateStatus).font(.subheadline).foregroundColor(.secondary)
            Button {
                Task { await updater.check() }
            } label: {
                Text(loc.t("Rechercher une mise à jour", "Check for updates"))
            }
            .buttonStyle(GlassButtonStyle())
            .disabled(updater.checking)
            if updater.available {
                Link(destination: Updater.pageURL) {
                    Label(loc.t("Ouvrir la page de téléchargement", "Open the download page"),
                          systemImage: "arrow.up.right.square")
                        .font(.subheadline)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private var forkCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(loc.t("Ce fork", "This fork")).font(.headline)
            Text(LocalizedStringKey(loc.t("Cette version iOS a été réalisée par **Akuu**.",
                                          "This iOS version was made by **Akuu**.")))
            Text(loc.t("Akuu ne détient **aucun droit** sur Upscayl, ses modèles, son logo ni son nom. Tout appartient à leurs créateurs et ayants droit listés ci-dessous.",
                       "Akuu owns **no rights** to Upscayl, its models, its logo or its name. Everything belongs to their respective creators and rights holders listed below.")
                .replacingOccurrences(of: "**", with: ""))
                .foregroundColor(.secondary)
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private var creditsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(loc.t("Appartient à", "Belongs to")).font(.headline)
            row("Upscayl", loc.t("© 2023 Nayam Amarshe & TGS963 et contributeurs",
                                 "© 2023 Nayam Amarshe & TGS963 and contributors"))
            row("Logo", "NicKoehler")
            row(loc.t("Real-ESRGAN (modèles)", "Real-ESRGAN (models)"), "© 2021 Xintao Wang et al.")
            row(loc.t("Contributeurs Upscayl", "Upscayl contributors"),
                "JanDeDinoMan, xanderfrangos, Fdawgs, keturn, aaronliu0130")
            row("Core ML, SwiftUI, iOS", "Apple Inc.")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(card)
    }

    private var licenseCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(loc.t("Licences et code source", "Licenses and source code")).font(.headline)
            Text(loc.t("Logiciel libre sous licence AGPL-3.0 (Upscayl). Real-ESRGAN : BSD-3-Clause.",
                       "Free software under the AGPL-3.0 license (Upscayl). Real-ESRGAN: BSD-3-Clause."))
                .font(.subheadline).foregroundColor(.secondary)
            link(loc.t("Upscayl (projet original)", "Upscayl (original project)"), "https://github.com/upscayl/upscayl")
            link("Real-ESRGAN", "https://github.com/xinntao/Real-ESRGAN")
            link(loc.t("Code source de ce fork", "Source code of this fork"), "https://github.com/akuuvfx/upscayl-ios")
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
