package com.retrodroid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * O Android só deixa capturar o áudio do jogo (para o vídeo) com um serviço em primeiro plano
 * do tipo "mediaProjection". Este serviço só existe enquanto o vídeo está sendo gravado.
 */
class RecordService : Service() {

    companion object {
        private var onReady: ((MediaProjection?) -> Unit)? = null
        private var projection: MediaProjection? = null

        fun start(ctx: Context, resultCode: Int, data: Intent, callback: (MediaProjection?) -> Unit) {
            onReady = callback
            val i = Intent(ctx, RecordService::class.java).putExtra("code", resultCode).putExtra("data", data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            ctx.stopService(Intent(ctx, RecordService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Gravação de vídeo", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "rec")
            .setContentTitle("RetroDroid")
            .setContentText("Gravando o jogo…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        var mp: MediaProjection? = null
        try {
            val code = intent?.getIntExtra("code", 0) ?: 0
            val data = intent?.getParcelableExtra<Intent>("data")
            if (data != null) {
                mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                mp?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
            }
        } catch (_: Exception) {
            mp = null
        }
        projection = mp
        val cb = onReady
        onReady = null
        cb?.invoke(mp)
        return START_NOT_STICKY
    }
}
