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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    val version = AppInfo.VERSION
    val scope = rememberCoroutineScope()
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
                Text(Loc.t("Fork non officiel", "Unofficial fork"), color = muted)
                Text("${Loc.t("Version", "Version")} $version", color = muted, fontSize = 12.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Loc.t("Mises à jour", "Updates"), fontWeight = FontWeight.Bold, color = onBg)
                val status = when {
                    Updater.checking -> Loc.t("Vérification…", "Checking…")
                    Updater.failed -> Loc.t("Impossible de vérifier (hors ligne ?).", "Could not check (offline?).")
                    Updater.available -> Loc.t("Version ${Updater.remote} disponible.", "Version ${Updater.remote} is available.")
                    Updater.remote != null -> Loc.t("Tu as la dernière version.", "You have the latest version.")
                    else -> "${Loc.t("Version installée", "Installed version")} : $version"
                }
                Text(status, color = muted, fontSize = 14.sp)
                GlassButton(
                    Loc.t("Rechercher une mise à jour", "Check for updates"),
                    Modifier.fillMaxWidth(), enabled = !Updater.checking
                ) { scope.launch { Updater.check() } }
                if (Updater.available) {
                    TextButton(onClick = { uri.openUri(Updater.APK_URL) }) {
                        Text(Loc.t("Télécharger l'APK", "Download the APK"))
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Loc.t("Ce fork", "This fork"), fontWeight = FontWeight.Bold, color = onBg)
                Text(
                    buildAnnotatedString {
                        append(Loc.t("Cette version Android a été réalisée par ", "This Android version was made by "))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Akuu") }
                        append(".")
                    },
                    color = onBg
                )
                Text(
                    Loc.t(
                        "Akuu ne détient aucun droit sur Upscayl, ses modèles, son logo ni son nom. " +
                            "Tout appartient à leurs créateurs et ayants droit listés ci-dessous.",
                        "Akuu owns no rights to Upscayl, its models, its logo or its name. " +
                            "Everything belongs to their respective creators and rights holders listed below."
                    ),
                    color = muted
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Loc.t("Appartient à", "Belongs to"), fontWeight = FontWeight.Bold, color = onBg)
                Credit("Upscayl", Loc.t("© 2023 Nayam Amarshe & TGS963 et contributeurs", "© 2023 Nayam Amarshe & TGS963 and contributors"))
                Credit("Logo", "NicKoehler")
                Credit(Loc.t("Real-ESRGAN (modèles)", "Real-ESRGAN (models)"), "© 2021 Xintao Wang et al.")
                Credit(
                    Loc.t("Modèles communautaires", "Community models"),
                    "Helaman (HFA2k), Foolhardy (Remacri), Kim2091 (Ultrasharp, Ultramix Balanced)"
                )
                Credit(Loc.t("Contributeurs Upscayl", "Upscayl contributors"), "JanDeDinoMan, xanderfrangos, Fdawgs, keturn, aaronliu0130")
                Credit("ncnn", "© Tencent")
                Credit("Android, Jetpack Compose", "Google LLC")
            }
        }

        GlassCard(Modifier.fillMaxWidth(), card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Loc.t("Licences et code source", "Licenses and source code"), fontWeight = FontWeight.Bold, color = onBg)
                Text(
                    Loc.t(
                        "Logiciel libre sous licence AGPL-3.0 (Upscayl). Real-ESRGAN : BSD-3-Clause. ncnn : BSD-3-Clause.",
                        "Free software under the AGPL-3.0 license (Upscayl). Real-ESRGAN: BSD-3-Clause. ncnn: BSD-3-Clause."
                    ),
                    color = muted, fontSize = 14.sp
                )
                TextButton(onClick = { uri.openUri("https://github.com/upscayl/upscayl") }) {
                    Text(Loc.t("Upscayl (projet original)", "Upscayl (original project)"))
                }
                TextButton(onClick = { uri.openUri("https://github.com/xinntao/Real-ESRGAN") }) { Text("Real-ESRGAN") }
                TextButton(onClick = { uri.openUri("https://github.com/akuuvfx/upscayl-ios") }) {
                    Text(Loc.t("Code source de ce fork", "Source code of this fork"))
                }
            }
        }

        Text(
            Loc.t(
                "Projet non officiel, ni affilié ni approuvé par Upscayl.",
                "Unofficial project, not affiliated with or endorsed by Upscayl."
            ),
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
