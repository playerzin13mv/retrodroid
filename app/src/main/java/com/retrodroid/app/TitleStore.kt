package com.retrodroid.app

import android.content.Context
import java.io.File

/** Nomes personalizados dos jogos (o nome do arquivo da ROM continua o mesmo). */
class TitleStore(context: Context) {
    private val p = context.getSharedPreferences("titles", Context.MODE_PRIVATE)

    fun get(rom: File): String? = p.getString(rom.name, null)?.takeIf { it.isNotBlank() }

    fun set(rom: File, title: String) {
        if (title.isBlank()) clear(rom) else p.edit().putString(rom.name, title.trim()).apply()
    }

    fun clear(rom: File) {
        p.edit().remove(rom.name).apply()
    }
}
