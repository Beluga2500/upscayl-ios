package fork.upscayl.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class OutputFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPG("JPG", "jpg", "image/jpeg"),
    WEBP("WEBP", "webp", "image/webp")
}

enum class AppTheme(val accent: Color, val halo: Color, private val fr: String, private val en: String) {
    OCEAN(Color(0xFF0A85FF), Color(0xFF59C7FA), "Océan", "Ocean"),
    VIOLET(Color(0xFF8F5CF5), Color(0xFFDB75F2), "Violet", "Violet"),
    FOREST(Color(0xFF29B861), Color(0xFF4DD9BF), "Forêt", "Forest"),
    SUNSET(Color(0xFFFF8024), Color(0xFFFF4D73), "Coucher de soleil", "Sunset"),
    ROSE(Color(0xFFFF4D8C), Color(0xFFFF9EC2), "Rose", "Rose"),
    GRAPHITE(Color(0xFF808A9E), Color(0xFF5C667A), "Graphite", "Graphite");

    val title: String get() = Loc.t(fr, en)
}

enum class Appearance(private val fr: String, private val en: String) {
    SYSTEM("Système", "System"),
    LIGHT("Clair", "Light"),
    DARK("Sombre", "Dark");

    val title: String get() = Loc.t(fr, en)
}

data class Prefs(
    val format: OutputFormat = OutputFormat.PNG,
    val scale: Int = 4,
    val useCustomWidth: Boolean = false,
    val customWidth: Int = 0,
    val compression: Int = 0,
    val tileSize: Int = 0,
    val tta: Boolean = false,
    val doubleUpscayl: Boolean = false,
    val useGpu: Boolean = true,
    val autoSave: Boolean = false,
    val notifications: Boolean = false,
    val theme: AppTheme = AppTheme.OCEAN,
    val appearance: Appearance = Appearance.SYSTEM
)

object AppInfo {
    /** 0.<mises à jour de fonctionnalités>.<correctifs>  (voir CHANGELOG.md) */
    const val VERSION = "0.3.1"
}

/** Vrai quand l'app est au premier plan (sert à décider d'envoyer une notification). */
object AppState {
    @Volatile
    var foreground = false
}

object AppSettings {
    var p by mutableStateOf(Prefs())
        private set

    private var sp: SharedPreferences? = null

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    fun init(context: Context) {
        val s = context.getSharedPreferences("upscayl_prefs", Context.MODE_PRIVATE)
        sp = s
        val d = Prefs()
        p = sanitize(
            Prefs(
                format = enumOr(s.getString("format", null), d.format),
                scale = s.getInt("scale", d.scale),
                useCustomWidth = s.getBoolean("useCustomWidth", d.useCustomWidth),
                customWidth = s.getInt("customWidth", d.customWidth),
                compression = s.getInt("compression", d.compression),
                tileSize = s.getInt("tileSize", d.tileSize),
                tta = s.getBoolean("tta", d.tta),
                doubleUpscayl = s.getBoolean("doubleUpscayl", d.doubleUpscayl),
                useGpu = s.getBoolean("useGpu", d.useGpu),
                autoSave = s.getBoolean("autoSave", d.autoSave),
                notifications = s.getBoolean("notifications", d.notifications),
                theme = enumOr(s.getString("theme", null), d.theme),
                appearance = enumOr(s.getString("appearance", null), d.appearance)
            )
        )
    }

    fun update(change: (Prefs) -> Prefs) {
        p = sanitize(change(p))
        save()
    }

    fun reset() {
        p = Prefs()
        save()
    }

    private fun sanitize(x: Prefs) = x.copy(
        scale = x.scale.coerceIn(1, 16),
        customWidth = x.customWidth.coerceIn(0, 16384),
        compression = x.compression.coerceIn(0, 100),
        tileSize = x.tileSize.coerceIn(0, 1024)
    )

    private fun save() {
        val e = sp?.edit() ?: return
        val x = p
        e.putString("format", x.format.name)
        e.putInt("scale", x.scale)
        e.putBoolean("useCustomWidth", x.useCustomWidth)
        e.putInt("customWidth", x.customWidth)
        e.putInt("compression", x.compression)
        e.putInt("tileSize", x.tileSize)
        e.putBoolean("tta", x.tta)
        e.putBoolean("doubleUpscayl", x.doubleUpscayl)
        e.putBoolean("useGpu", x.useGpu)
        e.putBoolean("autoSave", x.autoSave)
        e.putBoolean("notifications", x.notifications)
        e.putString("theme", x.theme.name)
        e.putString("appearance", x.appearance.name)
        e.apply()
    }
}

object Notifier {
    private const val CHANNEL = "upscayl_done"

    fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Upscayl", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}
