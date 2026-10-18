package com.retrodroid.app

import android.content.Context

/**
 * Idiomas: pt (Português BR), en (English), es (Español), zh (中文).
 * O texto original do app é o português: tr("texto em português") devolve a tradução do idioma escolhido.
 */
object I18n {
    var lang: String = "pt"

    val options = listOf("pt" to "Português (BR)", "en" to "English", "es" to "Español", "zh" to "中文 (普通话)")

    fun load(ctx: Context) {
        lang = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "pt") ?: "pt"
    }

    fun save(ctx: Context, l: String) {
        lang = l
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", l).apply()
    }

    fun t(pt: String): String {
        val idx = when (lang) { "en" -> 0; "es" -> 1; "zh" -> 2; else -> return pt }
        return Dict.map[pt]?.getOrNull(idx) ?: pt
    }
}

fun tr(pt: String): String = I18n.t(pt)

fun trf(pt: String, vararg args: Any): String = String.format(I18n.t(pt), *args)
