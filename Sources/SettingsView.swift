import SwiftUI

private struct SettingsSection<Content: View>: View {
    let title: String
    var footer: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundColor(.secondary)
                .padding(.horizontal, 6)
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glass(RoundedRectangle(cornerRadius: 22, style: .continuous))
            if let footer {
                Text(footer)
                    .font(.footnote).foregroundColor(.secondary)
                    .padding(.horizontal, 6)
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var loc: Loc
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.theme) private var theme
    @State private var confirmReset = false

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 20) {
                Text(loc.t("Paramètres", "Settings"))
                    .font(.largeTitle.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)

                languageSection
                themeSection
                formatSection
                scaleSection
                widthSection
                compressionSection
                tileSection
                ttaSection
                computeSection
                savingSection
                resetSection

                Spacer().frame(height: 12)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .alert(loc.t("Réinitialiser les paramètres ?", "Reset settings?"), isPresented: $confirmReset) {
            Button(loc.t("Réinitialiser", "Reset"), role: .destructive) { settings.reset() }
            Button(loc.t("Annuler", "Cancel"), role: .cancel) {}
        } message: {
            Text(loc.t("Tous les réglages reviennent à leur valeur par défaut.",
                       "All settings go back to their default values."))
        }
    }

    // MARK: Sections

    private var languageSection: some View {
        SettingsSection(
            title: loc.t("Langue", "Language"),
            footer: loc.t("Toute l'application passe dans la langue choisie.",
                          "The whole app switches to the chosen language.")
        ) {
            Picker(loc.t("Langue", "Language"), selection: $loc.lang) {
                ForEach(Lang.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var themeSection: some View {
        SettingsSection(
            title: loc.t("Thème", "Theme"),
            footer: settings.p.theme.title
        ) {
            HStack(spacing: 14) {
                ForEach(AppTheme.allCases) { t in
                    Button {
                        withAnimation(.easeInOut(duration: 0.25)) { settings.p.theme = t }
                    } label: {
                        Circle()
                            .fill(LinearGradient(colors: [t.accent, t.halo],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 34, height: 34)
                            .overlay(
                                Circle()
                                    .stroke(Color.primary.opacity(settings.p.theme == t ? 0.85 : 0), lineWidth: 2)
                                    .padding(-5)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)

            Picker(loc.t("Apparence", "Appearance"), selection: $settings.p.appearance) {
                ForEach(AppearanceMode.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var formatSection: some View {
        SettingsSection(
            title: loc.t("Enregistrer l'image en", "Save image as"),
            footer: loc.t("Le WebP n'est pas pris en charge par iOS : le HEIC offre une compression comparable.",
                          "WebP is not supported by iOS: HEIC offers comparable compression.")
        ) {
            Picker(loc.t("Format", "Format"), selection: $settings.p.format) {
                ForEach(OutputFormat.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var scaleSection: some View {
        SettingsSection(
            title: "\(loc.t("Échelle de l'image", "Image scale")) (\(settings.p.scale)×)",
            footer: loc.t("Au-delà de 4×, l'image est seulement redimensionnée, sans IA.",
                          "Anything above 4× only resizes the image and does not use AI upscaling.")
        ) {
            Slider(
                value: Binding(
                    get: { Double(settings.p.scale) },
                    set: { settings.p.scale = Int($0.rounded()) }),
                in: 1...16, step: 1)
            .tint(theme.accent)
        }
    }

    private var widthSection: some View {
        SettingsSection(
            title: loc.t("Largeur de sortie personnalisée", "Custom output width"),
            footer: loc.t("La hauteur s'ajuste automatiquement. Activer ceci remplace le réglage d'échelle.",
                          "The height is adjusted automatically. Enabling this overrides the scale setting.")
        ) {
            Toggle(loc.t("Utiliser une largeur personnalisée", "Use a custom width"),
                   isOn: $settings.p.useCustomWidth)
            HStack {
                TextField("0", value: $settings.p.customWidth, format: .number.grouping(.never))
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .disabled(!settings.p.useCustomWidth)
                Text("px").foregroundColor(.secondary)
            }
            .opacity(settings.p.useCustomWidth ? 1 : 0.45)
        }
    }

    private var compressionSection: some View {
        SettingsSection(
            title: "\(loc.t("Compression de l'image", "Image compression")) (\(settings.p.compression) %)",
            footer: loc.t("Concerne le JPG et le HEIC (0 = qualité maximale). Le PNG reste sans perte.",
                          "Applies to JPG and HEIC (0 = best quality). PNG stays lossless.")
        ) {
            Slider(
                value: Binding(
                    get: { Double(settings.p.compression) },
                    set: { settings.p.compression = Int($0.rounded()) }),
                in: 0...100, step: 1)
            .tint(theme.accent)
        }
    }

    private var tileSection: some View {
        SettingsSection(
            title: loc.t("Taille de tuile personnalisée", "Custom tile size"),
            footer: loc.t("0 = auto. Utilise la taille disponible la plus proche (64 à 256 selon le modèle). Des tuiles plus grandes sont plus rapides mais demandent plus de mémoire.",
                          "0 = auto. Uses the closest available size (64 to 256 depending on the model). Larger tiles are faster but use more memory.")
        ) {
            TextField(loc.t("0 = auto", "0 = auto"), value: $settings.p.tileSize, format: .number.grouping(.never))
                .keyboardType(.numberPad)
        }
    }

    private var ttaSection: some View {
        SettingsSection(
            title: "TTA",
            footer: loc.t("Active l'augmentation au moment du test pour de meilleurs résultats (par exemple en supprimant des artefacts), MAIS le temps de traitement est multiplié par 8 !",
                          "Enable Test Time Augmentation for better results, such as removing artifacts BUT this will increase the processing time by 8x!")
        ) {
            Toggle(loc.t("Mode TTA", "TTA mode"), isOn: $settings.p.tta)
        }
    }

    private var computeSection: some View {
        SettingsSection(
            title: loc.t("Unité de calcul", "Compute unit"),
            footer: loc.t("Remplace le choix du GPU de la version PC. « Automatique » convient dans la plupart des cas.",
                          "Replaces the GPU ID option of the PC version. “Automatic” is fine in most cases.")
        ) {
            HStack {
                Text(loc.t("Traitement sur", "Run on"))
                Spacer()
                Menu {
                    Picker(loc.t("Unité de calcul", "Compute unit"), selection: $settings.p.compute) {
                        ForEach(ComputeChoice.allCases) { Text($0.title).tag($0) }
                    }
                } label: {
                    HStack(spacing: 5) {
                        Text(settings.p.compute.title)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundColor(theme.accent)
                }
            }
        }
    }

    private var savingSection: some View {
        SettingsSection(
            title: loc.t("Enregistrement et notifications", "Saving and notifications"),
            footer: loc.t("Les notifications n'arrivent que si l'app est encore active en arrière-plan : garde-la ouverte pendant un agrandissement.",
                          "Notifications only arrive if the app is still active in the background: keep it open during an upscale.")
        ) {
            Toggle(loc.t("Enregistrer automatiquement dans Photos", "Save automatically to Photos"),
                   isOn: $settings.p.autoSave)
            Toggle(loc.t("Notification de fin", "Notify when finished"),
                   isOn: $settings.p.notifications)
                .onChange(of: settings.p.notifications) { on in
                    if on { Notifier.requestAuthorization() }
                }
        }
    }

    private var resetSection: some View {
        SettingsSection(title: loc.t("Réinitialiser Upscayl", "Reset Upscayl")) {
            Button {
                confirmReset = true
            } label: {
                Text(loc.t("Réinitialiser les paramètres", "Reset settings"))
            }
            .buttonStyle(GlassButtonStyle())
        }
    }
}
