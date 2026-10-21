package fork.upscayl.android

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF4D84FA)
private val Violet = Color(0xFF7F5CF3)

@Composable
fun UpscaylTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Blue, background = Color(0xFF0B0F1A), onBackground = Color(0xFFEDF0FA))
        else lightColorScheme(primary = Blue, background = Color(0xFFF6F7FB), onBackground = Color(0xFF14192B)),
        content = content
    )
}

/** Fond dégradé statique (aucun flou, aucun calcul par frame). */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) listOf(Color(0xFF17306B), Color(0xFF2E1B5C), Color(0xFF0B0F1A))
    else listOf(Color(0xFFBFD6FF), Color(0xFFDCCFFF), Color(0xFFF6F7FB))
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
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
    val dark = isSystemInDarkTheme()
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
    val base = modifier
        .height(50.dp)
        .alpha(if (enabled) 1f else 0.45f)
    if (prominent) {
        Box(
            base
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Blue, Violet)), CircleShape)
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
    GlassCard(Modifier.fillMaxWidth().height(46.dp), CircleShape) {
        Row(Modifier.fillMaxSize().padding(3.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (sel) Blue.copy(alpha = 0.92f) else Color.Transparent)
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
