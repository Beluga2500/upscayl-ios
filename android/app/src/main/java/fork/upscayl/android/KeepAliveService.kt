package fork.upscayl.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Garde le processus actif pendant un agrandissement : le calcul continue quand
 * l'app passe en arrière-plan. Le travail lui-même est fait par le ViewModel.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pct = intent?.getIntExtra("pct", 0) ?: 0
        val text = intent?.getStringExtra("text") ?: "Upscayl"
        startForeground(
            Notifier.PROGRESS_ID,
            Notifier.buildProgress(this, pct, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_NOT_STICKY
    }
}
