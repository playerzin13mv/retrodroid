// ARQUIVO GERADO (layout.py). Posições: x = âncora (L/R/C) + ox*H, y = fy*H, largura = size*H
package com.retrodroid.app

import android.view.KeyEvent

enum class Kind { BUTTON, DPAD, STICK, CBTN }

class LayoutItem(val id: String, val tex: String, val kind: Kind, val key: Int,
                 val anchor: Char, val ox: Float, val fy: Float, val size: Float)

object Layouts {
    fun forSystem(s: GameSystem): List<LayoutItem> = when (s) {
        GameSystem.SNES -> listOf(
            LayoutItem("dpad", "dpad", Kind.DPAD, 0, 'L', 0.3100f, 0.6900f, 0.5000f),
            LayoutItem("a", "botao_a", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_B, 'R', 0.2500f, 0.6930f, 0.1900f),
            LayoutItem("b", "botao_b", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_A, 'R', 0.4020f, 0.8450f, 0.1900f),
            LayoutItem("x", "botao_x", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_Y, 'R', 0.4020f, 0.5410f, 0.1900f),
            LayoutItem("y", "botao_y", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_X, 'R', 0.5540f, 0.6930f, 0.1900f),
            LayoutItem("l", "botao_l", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_L1, 'L', 0.2200f, 0.1400f, 0.3200f),
            LayoutItem("r", "botao_r", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_R1, 'R', 0.2200f, 0.1400f, 0.3200f),
            LayoutItem("select", "botao_select", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_SELECT, 'C', -0.1680f, 0.8800f, 0.2800f),
            LayoutItem("start", "botao_start", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_START, 'C', 0.1680f, 0.8800f, 0.2800f)
        )
        GameSystem.MEGADRIVE -> listOf(
            LayoutItem("dpad", "dpad", Kind.DPAD, 0, 'L', 0.3100f, 0.6900f, 0.5000f),
            LayoutItem("a", "botao_a", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_X, 'R', 0.4900f, 0.8600f, 0.1600f),
            LayoutItem("b", "botao_b", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_A, 'R', 0.3150f, 0.8600f, 0.1600f),
            LayoutItem("c", "botao_c", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_B, 'R', 0.1400f, 0.8600f, 0.1600f),
            LayoutItem("x", "botao_x", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_L1, 'R', 0.4900f, 0.6700f, 0.1600f),
            LayoutItem("y", "botao_y", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_Y, 'R', 0.3150f, 0.6700f, 0.1600f),
            LayoutItem("z", "botao_z", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_R1, 'R', 0.1400f, 0.6700f, 0.1600f),
            LayoutItem("mode", "botao_mode", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_SELECT, 'C', -0.1680f, 0.8800f, 0.2600f),
            LayoutItem("start", "botao_start", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_START, 'C', 0.1680f, 0.8800f, 0.2600f)
        )
        GameSystem.NES -> listOf(
            LayoutItem("dpad", "dpad", Kind.DPAD, 0, 'L', 0.3100f, 0.6900f, 0.5000f),
            LayoutItem("b", "botao_b", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_A, 'R', 0.4400f, 0.7400f, 0.2000f),
            LayoutItem("a", "botao_a", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_B, 'R', 0.1900f, 0.7400f, 0.2000f),
            LayoutItem("select", "botao_select", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_SELECT, 'C', -0.1680f, 0.8800f, 0.2800f),
            LayoutItem("start", "botao_start", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_START, 'C', 0.1680f, 0.8800f, 0.2800f)
        )
        GameSystem.N64 -> listOf(
            LayoutItem("stick", "stick_base", Kind.STICK, 0, 'L', 0.2900f, 0.7100f, 0.4600f),
            LayoutItem("a", "botao_a", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_A, 'R', 0.2000f, 0.7800f, 0.1900f),
            LayoutItem("b", "botao_b", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_X, 'R', 0.4200f, 0.6900f, 0.1900f),
            LayoutItem("c_up", "n64_c_up", Kind.CBTN, 0, 'R', 0.2500f, 0.3150f, 0.1100f),
            LayoutItem("c_down", "n64_c_down", Kind.CBTN, 0, 'R', 0.2500f, 0.5250f, 0.1100f),
            LayoutItem("c_left", "n64_c_left", Kind.CBTN, 0, 'R', 0.3550f, 0.4200f, 0.1100f),
            LayoutItem("c_right", "n64_c_right", Kind.CBTN, 0, 'R', 0.1450f, 0.4200f, 0.1100f),
            LayoutItem("l", "botao_l", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_L1, 'L', 0.2200f, 0.1400f, 0.3200f),
            LayoutItem("r", "botao_r", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_R1, 'R', 0.2200f, 0.1400f, 0.3200f),
            LayoutItem("z", "n64_z", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_L2, 'L', 0.1600f, 0.3100f, 0.2000f),
            LayoutItem("start", "botao_start", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_START, 'C', 0.0000f, 0.8800f, 0.2800f)
        )
    }
}
