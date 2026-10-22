package fork.upscayl.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Loc.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            UpscaylTheme { Root() }
        }
    }
}

@Composable
fun Root(vm: UpscaleViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                GlassCard(
                    Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    CircleShape
                ) {
                    NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0)) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Star, null) },
                            label = { Text(Loc.t("Agrandir", "Upscale")) }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Info, null) },
                            label = { Text(Loc.t("À propos", "About")) }
                        )
                    }
                }
            }
        ) { padding ->
            if (tab == 0) UpscaleScreen(vm, Modifier.padding(bottom = padding.calculateBottomPadding()))
            else AboutScreen(Modifier.padding(bottom = padding.calculateBottomPadding()))
        }
    }
}
