package fork.upscayl.android

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun isDark(): Boolean = when (AppSettings.p.appearance) {
    Appearance.SYSTEM -> isSystemInDarkTheme()
    Appearance.LIGHT -> false
    Appearance.DARK -> true
}

@Composable
fun UpscaylTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val accent = AppSettings.p.theme.accent
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    val scheme = if (dark) {
        darkColorScheme(primary = accent, background = Color(0xFF0B0F1A), onBackground = Color(0xFFEDF0FA))
    } else {
        lightColorScheme(primary = accent, background = Color(0xFFF6F7FB), onBackground = Color(0xFF14192B))
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}

/** Fond statique : deux halos très doux (aucun calcul par frame). */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isDark()
    val theme = AppSettings.p.theme
    Box(
        modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF0B0F1A) else Color(0xFFF6F7FB))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.accent.copy(alpha = if (dark) 0.34f else 0.20f), Color.Transparent),
                        center = Offset.Zero,
                        radius = size.maxDimension * 0.85f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.halo.copy(alpha = if (dark) 0.20f else 0.16f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = size.maxDimension * 0.9f
                    )
                )
            },
        content = content
    )
}

/** Surface verre : dégradé translucide + liseré clair. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isDark()
    val top = Color.White.copy(alpha = if (dark) 0.14f else 0.55f)
    val bottom = Color.White.copy(alpha = if (dark) 0.05f else 0.28f)
    val edge = Color.White.copy(alpha = if (dark) 0.22f else 0.75f)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)), shape)
            .border(1.dp, edge, shape),
        content = content
    )
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = AppSettings.p.theme.accent
    val base = modifier
        .height(52.dp)
        .alpha(if (enabled) 1f else 0.45f)
    if (prominent) {
        Box(
            base
                .clip(CircleShape)
                .background(accent, CircleShape)
                .background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)),
                    CircleShape
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    } else {
        GlassCard(base, CircleShape) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(text, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val accent = AppSettings.p.theme.accent
    GlassCard(Modifier.fillMaxWidth().height(46.dp), CircleShape) {
        Row(Modifier.fillMaxSize().padding(3.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (sel) accent.copy(alpha = 0.92f) else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (sel) Color.White else MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
