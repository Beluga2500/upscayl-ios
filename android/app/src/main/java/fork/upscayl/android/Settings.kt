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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val autoUpdateCheck: Boolean = true,
    val theme: AppTheme = AppTheme.OCEAN,
    val appearance: Appearance = Appearance.SYSTEM
)

object AppInfo {
    /** 0.<mises à jour de fonctionnalités>.<correctifs>  (voir CHANGELOG.md) */
    const val VERSION = "0.4.1"
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
                autoUpdateCheck = s.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
                theme = enumOr(s.getString("theme", null), d.theme),
                appearance = enumOr(s.getString("appearance", null), d.appearance)
            )
        )
    }

    /** Vrai une fois que l'autorisation de notifications a été demandée. */
    var notifAsked: Boolean
        get() = sp?.getBoolean("notifAsked", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("notifAsked", v)?.apply()
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
        e.putBoolean("autoUpdateCheck", x.autoUpdateCheck)
        e.putString("theme", x.theme.name)
        e.putString("appearance", x.appearance.name)
        e.apply()
    }
}

object Notifier {
    private const val CH_DONE = "upscayl_done"
    private const val CH_PROGRESS = "upscayl_progress"
    const val PROGRESS_ID = 10
    private const val DONE_ID = 1

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Upscayl", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, Loc.t("Progression", "Progress"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun launchIntent(context: Context): PendingIntent {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_IMMUTABLE)
    }

    fun buildProgress(context: Context, pct: Int, text: String): android.app.Notification {
        channels(context)
        return android.app.Notification.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Upscayl")
            .setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent(context))
            .build()
    }

    /** Met à jour la notification de progression (celle du service au premier plan). */
    fun progress(context: Context, pct: Int, text: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(PROGRESS_ID, buildProgress(context, pct, text))
    }

    fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        channels(context)
        val n = android.app.Notification.Builder(context, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launchIntent(context))
            .setAutoCancel(true)
            .build()
        nm.notify(DONE_ID, n)
    }
}

/** Compare la version installée à celle publiée sur la release « latest ». */
object Updater {
    private const val BASE = "https://github.com/akuuvfx/upscayl-ios/releases/download/latest/"
    const val APK_URL = BASE + "Upscayl-Android.apk"
    private const val VERSION_URL = BASE + "version-android.txt"

    var remote by mutableStateOf<String?>(null)
        private set
    var available by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var dismissed by mutableStateOf(false)

    suspend fun check() {
        if (checking) return
        checking = true
        failed = false
        try {
            val text = withContext(Dispatchers.IO) {
                val c = URL(VERSION_URL).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 10_000
                    c.readTimeout = 10_000
                    c.useCaches = false
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText().trim() else null
                } finally {
                    c.disconnect()
                }
            }
            if (text == null) {
                failed = true
            } else {
                remote = text
                available = isNewer(text, AppInfo.VERSION)
                if (available) dismissed = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            checking = false
        }
    }

    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }
}

object Eta {
    /** Durée restante lisible : « quelques secondes », « ≈ 45 s », « ≈ 3 min 20 s ». */
    fun text(seconds: Double): String {
        val t = seconds.roundToInt()
        return when {
            t < 5 -> Loc.t("quelques secondes", "a few seconds")
            t < 60 -> "≈ $t s"
            t < 3600 -> {
                val m = t / 60
                val r = t % 60
                if (r == 0) "≈ $m min" else "≈ $m min $r s"
            }
            else -> "≈ ${t / 3600} h ${(t % 3600) / 60} min"
        }
    }
}
