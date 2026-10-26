package fork.upscayl.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
private fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    GlassCard(
        Modifier.size(42.dp).alpha(if (enabled) 1f else 0.3f),
        CircleShape
    ) {
        Box(
            Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
fun UpscaleScreen(vm: UpscaleViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val prefs = AppSettings.p
    val accent = prefs.theme.accent

    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }

    // Un modèle personnalisé supprimé ne doit plus rester sélectionné
    LaunchedEffect(CustomModels.list, vm.model) {
        if (vm.model.isCustom && vm.model !in CustomModels.list) vm.model = UpscaleModel.STANDARD.ref
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) vm.setPicked(uris)
    }
    val pickPhotos = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Autorisation de notification (progression en arrière-plan), demandée une seule fois
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val onUpscale = {
        if (Build.VERSION.SDK_INT >= 33 && !AppSettings.notifAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AppSettings.notifAsked = true
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
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

        // Bandeau de mise à jour
        if (Updater.available && !Updater.dismissed) {
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Loc.t("Nouvelle version disponible", "New version available"),
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = onBg
                        )
                        Text(
                            "${AppInfo.VERSION} → ${Updater.remote ?: ""}",
                            fontSize = 12.sp, color = onBg.copy(alpha = 0.6f)
                        )
                    }
                    TextButton(onClick = { uriHandler.openUri(Updater.APK_URL) }) {
                        Text(Loc.t("Télécharger", "Download"), color = accent, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "✕", color = onBg.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { Updater.dismissed = true }.padding(6.dp)
                    )
                }
            }
        }

        // Aperçu / comparaison
        GlassCard(Modifier.weight(1f).fillMaxWidth(), RoundedCornerShape(32.dp)) {
            val before = vm.displayBefore
            val after = vm.displayAfter
            when {
                before != null && after != null -> key(vm.current?.id) {
                    CompareView(before, after, Modifier.fillMaxSize())
                }
                before != null -> {
                    val img = remember(before) { before.asImageBitmap() }
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                vm.items.isNotEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = accent)
                else -> Column(
                    Modifier.fillMaxSize().clickable { pickPhotos() },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("+", fontSize = 44.sp, fontWeight = FontWeight.Light, color = onBg)
                    Text(Loc.t("Ajouter des photos", "Add photos"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = onBg)
                    Text(
                        Loc.t("Touche pour choisir une ou plusieurs images", "Tap to choose one or more images"),
                        fontSize = 13.sp, color = onBg.copy(alpha = 0.6f)
                    )
                }
            }

            // Flèches gauche / droite + compteur (lot de plusieurs images)
            if (vm.items.size > 1) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                ) { ArrowButton("‹", vm.selected > 0) { vm.previous() } }
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                ) { ArrowButton("›", vm.selected < vm.items.size - 1) { vm.next() } }

                val status = when (vm.current?.status) {
                    ItemStatus.DONE -> " ✓"
                    ItemStatus.RUNNING -> " …"
                    ItemStatus.FAILED -> " !"
                    else -> ""
                }
                GlassCard(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), RoundedCornerShape(50)) {
                    Text(
                        "${vm.selected + 1} / ${vm.items.size}$status",
                        color = onBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                            (UpscaleModel.entries.map { it.ref } + CustomModels.list).forEach { m ->
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
                    val idx = vm.runningIndex
                    val pct = (vm.progress * 100).toInt()
                    val eta = vm.eta?.let { " — $it ${Loc.t("restantes", "left")}" } ?: ""
                    val batch = if (vm.items.size > 1 && idx >= 0) "${Loc.t("Image", "Image")} ${idx + 1}/${vm.items.size} — " else ""
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$batch$pct %$eta",
                            fontSize = 12.sp, color = onBg.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.cancel() }) { Text(Loc.t("Annuler", "Cancel")) }
                    }
                    Text(
                        Loc.t("Le calcul continue si tu quittes l'app.", "Processing continues if you leave the app."),
                        fontSize = 11.sp, color = onBg.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("Photos", Modifier.weight(1f), enabled = !vm.running) { pickPhotos() }
            GlassButton(
                if (vm.items.size > 1) "${Loc.t("Agrandir", "Upscale")} (${vm.items.size})" else Loc.t("Agrandir", "Upscale"),
                Modifier.weight(2f), prominent = true,
                enabled = vm.items.isNotEmpty() && !vm.running
            ) { onUpscale() }
        }

        val cur = vm.current
        val file = cur?.file
        if (cur != null && cur.status == ItemStatus.DONE && file != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(Loc.t("Enregistrer", "Save"), Modifier.weight(1f)) { vm.saveToGallery() }
                GlassButton(Loc.t("Partager", "Share"), Modifier.weight(1f)) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val mime = OutputFormat.values().firstOrNull { it.ext == file.extension }?.mime ?: "image/png"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, Loc.t("Partager", "Share")))
                }
            }
            if (vm.doneCount > 1 && !vm.running) {
                GlassButton(
                    "${Loc.t("Tout enregistrer", "Save all")} (${vm.doneCount})",
                    Modifier.fillMaxWidth()
                ) { vm.saveAll() }
            }
            Text(
                Loc.t("Résultat : ${cur.width} × ${cur.height} px", "Result: ${cur.width} × ${cur.height} px"),
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
