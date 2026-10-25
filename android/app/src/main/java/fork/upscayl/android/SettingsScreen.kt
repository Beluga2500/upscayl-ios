package fork.upscayl.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
private fun Section(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = onBg.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 6.dp)
        )
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
        if (footer != null) {
            Text(
                footer, fontSize = 12.sp, color = onBg.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(value: Int, enabled: Boolean, placeholder: String, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { s -> onChange(s.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0) },
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val p = AppSettings.p
    val onBg = MaterialTheme.colorScheme.onBackground
    var confirmReset by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            Loc.t("Paramètres", "Settings"), fontSize = 34.sp, fontWeight = FontWeight.Bold,
            color = onBg, modifier = Modifier.padding(top = 8.dp)
        )

        // Langue
        Section(
            Loc.t("Langue", "Language"),
            Loc.t("Toute l'application passe dans la langue choisie.", "The whole app switches to the chosen language.")
        ) {
            val langs = Lang.values().toList()
            Segmented(langs.map { it.label }, langs.indexOf(Loc.lang), true) { Loc.set(langs[it]) }
        }

        // Thème
        Section(Loc.t("Thème", "Theme"), p.theme.title) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AppTheme.values().forEach { t ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(Brush.linearGradient(listOf(t.accent, t.halo)), CircleShape)
                            .then(
                                if (p.theme == t) Modifier.border(2.dp, onBg.copy(alpha = 0.85f), CircleShape) else Modifier
                            )
                            .clickable { AppSettings.update { it.copy(theme = t) } }
                    )
                }
            }
            val modes = Appearance.values().toList()
            Segmented(modes.map { it.title }, modes.indexOf(p.appearance), true) {
                AppSettings.update { s -> s.copy(appearance = modes[it]) }
            }
        }

        // Format
        Section(
            Loc.t("Enregistrer l'image en", "Save image as"),
            null
        ) {
            val formats = OutputFormat.values().toList()
            Segmented(formats.map { it.label }, formats.indexOf(p.format), true) {
                AppSettings.update { s -> s.copy(format = formats[it]) }
            }
        }

        // Échelle
        Section(
            "${Loc.t("Échelle de l'image", "Image scale")} (${p.scale}×)",
            if (p.doubleUpscayl) {
                Loc.t(
                    "Avec Double Upscayl, toutes les échelles jusqu'à 16× utilisent l'IA.",
                    "With Double Upscayl, every scale up to 16× uses AI upscaling."
                )
            } else {
                Loc.t(
                    "Au-delà de 4×, l'image est seulement redimensionnée, sans IA (sauf avec Double Upscayl).",
                    "Anything above 4× only resizes the image and does not use AI upscaling (except with Double Upscayl)."
                )
            }
        ) {
            Slider(
                value = p.scale.toFloat(),
                onValueChange = { v -> AppSettings.update { it.copy(scale = v.roundToInt(), useCustomWidth = false) } },
                valueRange = 1f..16f,
                steps = 14
            )
        }

        // Double Upscayl
        Section(
            "Double Upscayl",
            Loc.t(
                "Agrandit l'image deux fois avec l'IA pour une meilleure qualité (jusqu'à ×16). Le traitement est nettement plus long et demande plus de mémoire.",
                "Upscales the image twice with AI for higher quality (up to ×16). Processing takes much longer and uses more memory."
            )
        ) {
            SwitchRow(Loc.t("Activer Double Upscayl", "Enable Double Upscayl"), p.doubleUpscayl) { on ->
                AppSettings.update { it.copy(doubleUpscayl = on) }
            }
        }

        // Largeur personnalisée
        Section(
            Loc.t("Largeur de sortie personnalisée", "Custom output width"),
            Loc.t(
                "La hauteur s'ajuste automatiquement. Activer ceci remplace le réglage d'échelle.",
                "The height is adjusted automatically. Enabling this overrides the scale setting."
            )
        ) {
            SwitchRow(Loc.t("Utiliser une largeur personnalisée", "Use a custom width"), p.useCustomWidth) { on ->
                AppSettings.update { it.copy(useCustomWidth = on) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    NumberField(p.customWidth, p.useCustomWidth, "0") { v ->
                        AppSettings.update { it.copy(customWidth = v) }
                    }
                }
                Text("px", color = onBg.copy(alpha = 0.6f))
            }
        }

        // Compression
        Section(
            "${Loc.t("Compression de l'image", "Image compression")} (${p.compression} %)",
            Loc.t(
                "Concerne le JPG et le WEBP (0 = qualité maximale). Le PNG reste sans perte.",
                "Applies to JPG and WEBP (0 = best quality). PNG stays lossless."
            )
        ) {
            Slider(
                value = p.compression.toFloat(),
                onValueChange = { v -> AppSettings.update { it.copy(compression = v.roundToInt()) } },
                valueRange = 0f..100f
            )
        }

        // Taille de tuile
        Section(
            Loc.t("Taille de tuile personnalisée", "Custom tile size"),
            Loc.t(
                "0 = auto. Des tuiles plus grandes sont plus rapides mais demandent plus de mémoire.",
                "0 = auto. Larger tiles are faster but use more memory."
            )
        ) {
            NumberField(p.tileSize, true, Loc.t("0 = auto", "0 = auto")) { v ->
                AppSettings.update { it.copy(tileSize = v) }
            }
        }

        // TTA
        Section(
            "TTA",
            Loc.t(
                "Active l'augmentation au moment du test pour de meilleurs résultats (par exemple en supprimant des artefacts), MAIS le temps de traitement est multiplié par 8 !",
                "Enable Test Time Augmentation for better results, such as removing artifacts BUT this will increase the processing time by 8x!"
            )
        ) {
            SwitchRow(Loc.t("Mode TTA", "TTA mode"), p.tta) { on -> AppSettings.update { it.copy(tta = on) } }
        }

        // GPU
        Section(
            "GPU",
            Loc.t(
                "Utilise le GPU via Vulkan. En cas d'échec, l'app retombe automatiquement sur le processeur.",
                "Uses the GPU through Vulkan. If it fails, the app automatically falls back to the CPU."
            )
        ) {
            SwitchRow(Loc.t("Utiliser le GPU (Vulkan)", "Use the GPU (Vulkan)"), p.useGpu) { on ->
                AppSettings.update { it.copy(useGpu = on) }
            }
        }

        // Enregistrement et notifications
        Section(
            Loc.t("Enregistrement et notifications", "Saving and notifications"),
            Loc.t(
                "Les notifications n'arrivent que si l'app est encore active en arrière-plan : garde-la ouverte pendant un agrandissement.",
                "Notifications only arrive if the app is still active in the background: keep it open during an upscale."
            )
        ) {
            SwitchRow(Loc.t("Enregistrer automatiquement dans Photos", "Save automatically to Photos"), p.autoSave) { on ->
                AppSettings.update { it.copy(autoSave = on) }
            }
            SwitchRow(Loc.t("Notification de fin", "Notify when finished"), p.notifications) { on ->
                AppSettings.update { it.copy(notifications = on) }
                if (on && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Réinitialiser
        Section(Loc.t("Réinitialiser Upscayl", "Reset Upscayl")) {
            GlassButton(Loc.t("Réinitialiser les paramètres", "Reset settings"), Modifier.fillMaxWidth()) {
                confirmReset = true
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(Loc.t("Réinitialiser les paramètres ?", "Reset settings?")) },
            text = {
                Text(
                    Loc.t(
                        "Tous les réglages reviennent à leur valeur par défaut.",
                        "All settings go back to their default values."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.reset()
                    confirmReset = false
                }) { Text(Loc.t("Réinitialiser", "Reset")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(Loc.t("Annuler", "Cancel")) }
            }
        )
    }
}
