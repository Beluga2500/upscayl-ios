package fork.upscayl.android

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Barre d'onglets en verre : la pastille suit le doigt et se range sur l'onglet le plus proche.
 * C'est le seul endroit qui change de page (aucun balayage sur le contenu).
 */
@Composable
fun GlassTabBar(
    items: List<Pair<ImageVector, String>>,
    progress: Float,
    lifted: Boolean,
    onScrub: (Float) -> Unit,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = AppSettings.p.theme.accent
    val dark = isDark()
    val density = LocalDensity.current
    val lift = animateFloatAsState(
        if (lifted) 1.07f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "lift"
    )
    val pad = 5.dp
    val barH = 64.dp

    BoxWithConstraints(modifier.fillMaxWidth().height(barH)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val padPx = with(density) { pad.toPx() }
        val itemWpx = (widthPx - 2 * padPx) / items.size
        val itemWdp = with(density) { itemWpx.toDp() }
        val maxIndex = (items.size - 1).toFloat()

        GlassCard(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var v = ((down.position.x - padPx - itemWpx / 2) / itemWpx).coerceIn(0f, maxIndex)
                        onScrub(v)
                        drag(down.id) { change ->
                            v = ((change.position.x - padPx - itemWpx / 2) / itemWpx).coerceIn(0f, maxIndex)
                            onScrub(v)
                            change.consume()
                        }
                        onSettle(v.roundToInt())
                    }
                },
            CircleShape
        ) {
            // Pastille de sélection
            Box(
                Modifier
                    .offset { IntOffset((padPx + progress * itemWpx).roundToInt(), with(density) { pad.roundToPx() }) }
                    .size(itemWdp, barH - pad * 2)
                    .graphicsLayer {
                        scaleX = lift.value
                        scaleY = lift.value
                    }
                    .background(Color.White.copy(alpha = if (dark) 0.16f else 0.62f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.28f else 0.85f), CircleShape)
            )

            Row(Modifier.fillMaxSize().padding(horizontal = pad)) {
                items.forEachIndexed { i, (icon, title) ->
                    val selected = abs(progress - i) < 0.5f
                    val tint = if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(title, color = tint, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
