package fork.upscayl.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Lang(val label: String) {
    FR("Français"),
    EN("English")
}

/** Langue de l'app (choisie dans « À propos », mémorisée). Changement instantané via l'état Compose. */
object Loc {
    var lang by mutableStateOf(Lang.FR)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("upscayl", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString("lang", null)?.let { s -> Lang.values().firstOrNull { it.name == s } }
        lang = saved ?: if (java.util.Locale.getDefault().language == "fr") Lang.FR else Lang.EN
    }

    fun set(l: Lang) {
        lang = l
        prefs?.edit()?.putString("lang", l.name)?.apply()
    }

    fun t(fr: String, en: String): String = if (lang == Lang.FR) fr else en
}
