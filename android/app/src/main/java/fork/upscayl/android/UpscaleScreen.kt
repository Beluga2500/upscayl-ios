package fork.upscayl.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
fun UpscaleScreen(vm: UpscaleViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val onBg = MaterialTheme.colorScheme.onBackground

    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Upscayl",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = onBg,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        GlassCard(Modifier.weight(1f).fillMaxWidth(), RoundedCornerShape(28.dp)) {
            val src = vm.source
            val res = vm.resultPreview
            when {
                src != null && res != null -> CompareView(src, res, Modifier.fillMaxSize())
                src != null -> {
                    val img = remember(src) { src.asImageBitmap() }
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                else -> Text(
                    "Choisis une photo à agrandir",
                    Modifier.align(Alignment.Center),
                    color = onBg.copy(alpha = 0.6f)
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var open by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Modèle", color = onBg)
                    Box {
                        TextButton(onClick = { open = true }, enabled = !vm.running) {
                            Text(vm.model.title + "  ▾")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            UpscaleModel.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.title) },
                                    onClick = {
                                        vm.model = m
                                        open = false
                                    }
                                )
                            }
                        }
                    }
                }

                val scales = listOf(2, 3, 4)
                Segmented(
                    options = scales.map { "×$it" },
                    selected = scales.indexOf(vm.scale).coerceAtLeast(0),
                    enabled = !vm.running
                ) { vm.scale = scales[it] }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GPU (Vulkan)", color = onBg)
                    Switch(checked = vm.useGpu, onCheckedChange = { vm.useGpu = it }, enabled = !vm.running)
                }

                if (vm.running) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${(vm.progress * 100).toInt()} % — garde l'app ouverte",
                            fontSize = 12.sp,
                            color = onBg.copy(alpha = 0.7f)
                        )
                        TextButton(onClick = { vm.cancel() }) { Text("Annuler") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Photo", Modifier.weight(1f), enabled = !vm.running) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            GlassButton(
                "Agrandir", Modifier.weight(1f), prominent = true,
                enabled = vm.source != null && !vm.running
            ) { vm.start() }
        }

        val res = vm.result
        if (res != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Enregistrer", Modifier.weight(1f)) { vm.saveToGallery() }
                GlassButton("Partager", Modifier.weight(1f), enabled = vm.exportFile != null) {
                    vm.exportFile?.let { f ->
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Partager"))
                    }
                }
            }
            Text(
                "Résultat : ${res.width} × ${res.height} px",
                fontSize = 12.sp,
                color = onBg.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        vm.message?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = onBg.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
