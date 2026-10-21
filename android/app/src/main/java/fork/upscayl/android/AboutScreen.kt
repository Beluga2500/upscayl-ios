package fork.upscayl.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = onBg.copy(alpha = 0.7f)
    val uri = LocalUriHandler.current
    val card = RoundedCornerShape(24.dp)

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painterResource(R.drawable.logo), null,
                    Modifier.size(84.dp).clip(RoundedCornerShape(20.dp))
                )
                Text("Upscayl Android", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onBg)
                Text("Fork non officiel", color = muted)
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ce fork", fontWeight = FontWeight.Bold, color = onBg)
                Text(
                    buildAnnotatedString {
                        append("Cette version Android a été réalisée par ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Akuu") }
                        append(".")
                    },
                    color = onBg
                )
                Text(
                    "Akuu ne détient aucun droit sur Upscayl, ses modèles, son logo ni son nom. " +
                        "Tout appartient à leurs créateurs et ayants droit listés ci-dessous.",
                    color = muted
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appartient à", fontWeight = FontWeight.Bold, color = onBg)
                Credit("Upscayl", "© 2023 Nayam Amarshe & TGS963 et contributeurs")
                Credit("Logo", "NicKoehler")
                Credit("Real-ESRGAN (modèles)", "© 2021 Xintao Wang et al.")
                Credit("Modèles communautaires", "Helaman (HFA2k), Foolhardy (Remacri), Kim2091 (Ultrasharp, Ultramix Balanced)")
                Credit("Contributeurs Upscayl", "JanDeDinoMan, xanderfrangos, Fdawgs, keturn, aaronliu0130")
                Credit("ncnn", "© Tencent")
                Credit("Android, Jetpack Compose", "Google LLC")
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Licences et code source", fontWeight = FontWeight.Bold, color = onBg)
                Text(
                    "Logiciel libre sous licence AGPL-3.0 (Upscayl). Real-ESRGAN : BSD-3-Clause. ncnn : BSD-3-Clause.",
                    color = muted, fontSize = 14.sp
                )
                TextButton(onClick = { uri.openUri("https://github.com/upscayl/upscayl") }) { Text("Upscayl (projet original)") }
                TextButton(onClick = { uri.openUri("https://github.com/xinntao/Real-ESRGAN") }) { Text("Real-ESRGAN") }
                TextButton(onClick = { uri.openUri("https://github.com/akuuvfx/upscayl-ios") }) { Text("Code source de ce fork") }
            }
        }

        Text(
            "Projet non officiel, ni affilié ni approuvé par Upscayl.",
            fontSize = 12.sp, color = muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Credit(title: String, detail: String) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = onBg, fontSize = 14.sp)
        Text(detail, color = onBg.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}
