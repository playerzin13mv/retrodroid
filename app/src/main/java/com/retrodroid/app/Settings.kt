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

    // ---- gravação de vídeo
    var recInputs: Boolean
        get() = p.getBoolean("recInputs", true)
        set(v) = p.edit().putBoolean("recInputs", v).apply()

    var recFps: Boolean
        get() = p.getBoolean("recFps", false)
        set(v) = p.edit().putBoolean("recFps", v).apply()

    var recControls: Boolean
        get() = p.getBoolean("recControls", false)
        set(v) = p.edit().putBoolean("recControls", v).apply()

    // ---- exibição
    /** true = imagem suavizada (interpolação); false = pixels nítidos. */
    var interpolation: Boolean
        get() = p.getBoolean("interpolation", true)
        set(v) = p.edit().putBoolean("interpolation", v).apply()

    /** Efeito de TV de tubo (CRT). */
    var crt: Boolean
        get() = p.getBoolean("crt", false)
        set(v) = p.edit().putBoolean("crt", v).apply()

    /** Troca A<->B e X<->Y (para o caso de a posição dos botões do controle vir invertida). */
    var swapAB: Boolean
        get() = p.getBoolean("swapAB", false)
        set(v) = p.edit().putBoolean("swapAB", v).apply()

    var debugInput: Boolean
        get() = p.getBoolean("debugInput", false)
        set(v) = p.edit().putBoolean("debugInput", v).apply()

    /** 20..100 (%) */
    var overlayOpacity: Int
        get() = p.getInt("overlayOpacity", 80)
        set(v) = p.edit().putInt("overlayOpacity", v).apply()

    /** 1, 2 ou 3 (velocidade do jogo) */
    var speed: Int
        get() = p.getInt("speed", 1)
        set(v) = p.edit().putInt("speed", v).apply()
}
