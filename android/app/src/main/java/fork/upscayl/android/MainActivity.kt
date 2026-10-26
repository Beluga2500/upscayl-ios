package fork.upscayl.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Loc.init(applicationContext)
        AppSettings.init(applicationContext)
        CustomModels.refresh(applicationContext)
        enableEdgeToEdge()
        setContent {
            UpscaylTheme { Root() }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.foreground = true
    }

    override fun onPause() {
        AppState.foreground = false
        super.onPause()
    }
}

@Composable
fun Root(vm: UpscaleViewModel = viewModel()) {
    val anim = remember { Animatable(0f) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) { anim.snapTo(page.toFloat()) }
    LaunchedEffect(Unit) { if (AppSettings.p.autoUpdateCheck) Updater.check() }

    val progress = scrub ?: anim.value

    fun settle(target: Int) {
        val t = target.coerceIn(0, 2)
        if (t != page) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        page = t
        val from = scrub
        scope.launch {
            if (from != null) anim.snapTo(from)
            scrub = null
            anim.animateTo(t.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    GlassBackground {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPad = 92.dp + navBottom

            // Les pages ne se déplacent qu'avec la barre d'onglets (aucun balayage sur le contenu)
            for (i in 0..2) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = (i - (scrub ?: anim.value)) * size.width }
                ) {
                    when (i) {
                        0 -> UpscaleScreen(vm, Modifier.padding(bottom = bottomPad))
                        1 -> SettingsScreen(Modifier.padding(bottom = bottomPad))
                        else -> AboutScreen(Modifier.padding(bottom = bottomPad))
                    }
                }
            }

            GlassTabBar(
                items = listOf(
                    Icons.Default.Star to Loc.t("Agrandir", "Upscale"),
                    Icons.Default.Settings to Loc.t("Paramètres", "Settings"),
                    Icons.Default.Info to Loc.t("À propos", "About")
                ),
                progress = progress,
                lifted = scrub != null,
                onScrub = { scrub = it },
                onSettle = { settle(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}
