package fork.upscayl.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UpscaleScreen(vm: UpscaleViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val prefs = AppSettings.p
    val accent = prefs.theme.accent

    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val pickPhoto = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Upscayl", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = onBg)
            Text(
                Loc.t("Agrandis tes images avec l'IA", "Enhance your images with AI"),
                fontSize = 14.sp, color = onBg.copy(alpha = 0.6f)
            )
        }

        // Aperçu / comparaison
        GlassCard(Modifier.weight(1f).fillMaxWidth(), RoundedCornerShape(32.dp)) {
            val src = vm.source
            val res = vm.resultPreview
            when {
                src != null && res != null -> CompareView(src, res, Modifier.fillMaxSize())
                src != null -> {
                    val img = remember(src) { src.asImageBitmap() }
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                else -> Column(
                    Modifier.fillMaxSize().clickable { pickPhoto() },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("+", fontSize = 44.sp, fontWeight = FontWeight.Light, color = onBg)
                    Text(Loc.t("Ajouter une photo", "Add a photo"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = onBg)
                    Text(
                        Loc.t("Touche pour choisir une image", "Tap to choose an image"),
                        fontSize = 13.sp, color = onBg.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Réglages rapides
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                var modelOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Loc.t("Modèle", "Model"), color = onBg)
                    Box {
                        TextButton(onClick = { modelOpen = true }, enabled = !vm.running) {
                            Text(vm.model.title + "  ▾", color = accent)
                        }
                        DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                            UpscaleModel.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.title) },
                                    onClick = {
                                        vm.model = m
                                        modelOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                Text(
                    vm.model.detail, fontSize = 12.sp, color = onBg.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                HorizontalDivider(color = onBg.copy(alpha = 0.12f))

                var scaleOpen by remember { mutableStateOf(false) }
                val scaleLabel = if (prefs.useCustomWidth && prefs.customWidth > 0) "${prefs.customWidth} px" else "×${prefs.scale}"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Loc.t("Échelle", "Scale"), color = onBg)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (prefs.doubleUpscayl) Chip("×2 IA", accent)
                        if (prefs.tta) Chip("TTA", accent)
                        Box {
                            TextButton(onClick = { scaleOpen = true }, enabled = !vm.running) {
                                Text("$scaleLabel  ▾", color = accent)
                            }
                            DropdownMenu(expanded = scaleOpen, onDismissRequest = { scaleOpen = false }) {
                                (1..16).forEach { n ->
                                    DropdownMenuItem(
                                        text = { Text("×$n") },
                                        onClick = {
                                            AppSettings.update { it.copy(scale = n, useCustomWidth = false) }
                                            scaleOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (vm.running) {
                    HorizontalDivider(color = onBg.copy(alpha = 0.12f))
                    LinearProgressIndicator(
                        progress = { vm.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        color = accent
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Loc.t(
                                "${(vm.progress * 100).toInt()} % — garde l'app ouverte",
                                "${(vm.progress * 100).toInt()} % — keep the app open"
                            ),
                            fontSize = 12.sp, color = onBg.copy(alpha = 0.7f)
                        )
                        TextButton(onClick = { vm.cancel() }) { Text(Loc.t("Annuler", "Cancel")) }
                    }
                }
            }
        }

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("Photo", Modifier.weight(1f), enabled = !vm.running) { pickPhoto() }
            GlassButton(
                Loc.t("Agrandir", "Upscale"), Modifier.weight(2f), prominent = true,
                enabled = vm.source != null && !vm.running
            ) { vm.start() }
        }

        val res = vm.result
        if (res != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(Loc.t("Enregistrer", "Save"), Modifier.weight(1f)) { vm.saveToGallery() }
                GlassButton(Loc.t("Partager", "Share"), Modifier.weight(1f), enabled = vm.exportFile != null) {
                    vm.exportFile?.let { f ->
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        val mime = OutputFormat.values().firstOrNull { it.ext == f.extension }?.mime ?: "image/png"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, Loc.t("Partager", "Share")))
                    }
                }
            }
            Text(
                Loc.t("Résultat : ${res.width} × ${res.height} px", "Result: ${res.width} × ${res.height} px"),
                fontSize = 12.sp, color = onBg.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }

        vm.message?.let {
            Text(
                it, fontSize = 12.sp, color = onBg.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
