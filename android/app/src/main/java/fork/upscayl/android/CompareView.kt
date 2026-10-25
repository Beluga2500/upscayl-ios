package fork.upscayl.android

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private fun clampPan(p: Offset, z: Float, w: Float, h: Float): Offset {
    val mx = (z - 1f) * w / 2f
    val my = (z - 1f) * h / 2f
    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
}

/** Avant / après : séparateur déplaçable, zoom au pincement, déplacement au doigt, double toucher. */
@Composable
fun CompareView(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after.asImageBitmap() }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val z = (zoom * zoomChange).coerceIn(1f, 10f)
                    zoom = z
                    pan = if (z <= 1.01f) Offset.Zero
                    else clampPan(pan + panChange, z, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (zoom > 1.01f) {
                        zoom = 1f
                        pan = Offset.Zero
                    } else {
                        zoom = 3f
                    }
                })
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }

        Image(
            a, null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationX = pan.x; translationY = pan.y
            },
            contentScale = ContentScale.Fit
        )
        Image(
            b, null,
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) {
                        this@drawWithContent.drawContent()
                    }
                }
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                },
            contentScale = ContentScale.Fit
        )

        // Séparateur
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * split - 1.dp)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.White.copy(alpha = 0.9f))
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * split - 17.dp)
                .size(34.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("↔", color = Color.Black, fontSize = 14.sp)
        }

        // Zone de saisie du séparateur (prioritaire sur le déplacement de l'image)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * split - 26.dp)
                .fillMaxHeight()
                .width(52.dp)
                .pointerInput(widthPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        split = (split + dragAmount.x / widthPx).coerceIn(0f, 1f)
                    }
                }
        )

        val onBg = MaterialTheme.colorScheme.onBackground
        GlassCard(Modifier.align(Alignment.TopStart).padding(8.dp), RoundedCornerShape(50)) {
            Text(
                Loc.t("Avant", "Before"), color = onBg, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        GlassCard(Modifier.align(Alignment.TopEnd).padding(8.dp), RoundedCornerShape(50)) {
            Text(
                Loc.t("Après", "After"), color = onBg, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }

        if (zoom > 1.01f) {
            GlassCard(Modifier.align(Alignment.BottomStart).padding(10.dp), RoundedCornerShape(50)) {
                Text(
                    String.format(Locale.US, "×%.1f  ✕", zoom),
                    color = onBg, fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            zoom = 1f
                            pan = Offset.Zero
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
