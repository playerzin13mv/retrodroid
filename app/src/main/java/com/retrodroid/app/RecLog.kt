package com.retrodroid.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Relatório passo a passo da última gravação (para descobrir por que o áudio falha). */
object RecLog {
    private val sb = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun clear() { sb.setLength(0) }

    @Synchronized fun add(msg: String) {
        sb.append(fmt.format(Date())).append("  ").append(msg).append('\n')
        if (sb.length > 30000) sb.delete(0, 8000)
    }

    @Synchronized fun text(ctx: Context): String {
        if (sb.isNotEmpty()) return sb.toString()
        val f = File(ctx.filesDir, "rec_log.txt")
        return if (f.exists()) f.readText() else ""
    }

    @Synchronized fun flush(ctx: Context) {
        try { File(ctx.filesDir, "rec_log.txt").writeText(sb.toString()) } catch (_: Exception) {}
    }
}
