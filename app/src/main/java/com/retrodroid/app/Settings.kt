package com.retrodroid.app

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var showFps: Boolean
        get() = p.getBoolean("showFps", false)
        set(v) = p.edit().putBoolean("showFps", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    /** 20..100 (%) */
    var overlayOpacity: Int
        get() = p.getInt("overlayOpacity", 80)
        set(v) = p.edit().putInt("overlayOpacity", v).apply()

    /** 1, 2 ou 3 (velocidade do jogo) */
    var speed: Int
        get() = p.getInt("speed", 1)
        set(v) = p.edit().putInt("speed", v).apply()
}
