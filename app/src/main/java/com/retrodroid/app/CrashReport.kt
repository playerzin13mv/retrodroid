package com.retrodroid.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Guarda o erro quando o app fecha de repente e mostra na próxima vez que você abrir o app. */
object CrashReport {

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(app.filesDir, "crash.txt").writeText("Thread: ${t.name}\n" + Log.getStackTraceString(e))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Texto do último erro (Java ou nativo), ou null se não houve. */
    fun pending(ctx: Context): String? {
        var nativeText: String? = null
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
                val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
                if (info != null && info.timestamp > prefs.getLong("seen", 0L)) {
                    prefs.edit().putLong("seen", info.timestamp).apply()
                    if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        nativeText = "CRASH NATIVO (erro dentro do core/emulador)\n" +
                            "descrição: ${info.description}\nstatus: ${info.status}"
                    }
                }
            } catch (_: Exception) {
            }
        }
        val f = File(ctx.filesDir, "crash.txt")
        if (f.exists()) return f.readText()
        return nativeText
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "crash.txt").delete()
    }
}
