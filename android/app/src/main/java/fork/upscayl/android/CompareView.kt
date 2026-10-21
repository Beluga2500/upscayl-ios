package fork.upscayl.android

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompareView(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after.asImageBitmap() }

    BoxWithConstraints(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                split = (down.position.x / size.width).coerceIn(0f, 1f)
                drag(down.id) { change ->
                    split = (change.position.x / size.width).coerceIn(0f, 1f)
                    change.consume()
                }
            }
        }
    ) {
        Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(
            b, null,
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentScale = ContentScale.Fit
        )

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * split - 1.dp)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.White)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * split - 14.dp)
                .size(28.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("↔", color = Color.Black, fontSize = 14.sp)
        }

        GlassCard(Modifier.align(Alignment.TopStart).padding(8.dp), RoundedCornerShape(50)) {
            Text("Avant", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 11.sp)
        }
        GlassCard(Modifier.align(Alignment.TopEnd).padding(8.dp), RoundedCornerShape(50)) {
            Text("Après", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 11.sp)
        }
    }
}
