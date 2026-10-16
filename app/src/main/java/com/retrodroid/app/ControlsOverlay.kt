package com.retrodroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Controles na tela, desenhados com os PNGs (drawable-nodpi).
 * Uma única View trata todos os dedos, então dá pra apertar vários botões
 * ao mesmo tempo e deslizar o dedo entre botões.
 *
 * Mapeamento: o LibretroDroid converte as teclas do Android assim:
 *   BUTTON_A -> RetroPad B (baixo) | BUTTON_B -> RetroPad A (direita)
 *   BUTTON_X -> RetroPad Y (esquerda) | BUTTON_Y -> RetroPad X (cima)
 * Se algum botão ficar trocado num jogo, é só mexer na tabela em buildControls().
 */
class ControlsOverlay(
    context: Context,
    private val system: GameSystem,
    private val onKey: (down: Boolean, keyCode: Int) -> Unit
) : View(context) {

    private class Control(
        val name: String,
        val keyCode: Int,
        val released: Bitmap,
        val pressed: Bitmap,
        val widthRatio: Float,   // largura relativa à altura da tela
        val rect: RectF = RectF()
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 200 }

    private fun bmp(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        return BitmapFactory.decodeResource(resources, id)
    }

    // D-Pad
    private val dpadIdle = bmp("dpad_solto")
    private val dpadPressed = HashMap<String, Bitmap>()
    private val dpadRect = RectF()
    private var dpadMask = 0

    private val controls = ArrayList<Control>()
    private val down = HashSet<Control>()

    private companion object {
        const val UP = 1; const val DOWN = 2; const val LEFT = 4; const val RIGHT = 8
    }

    init {
        for (n in listOf(
            "up", "down", "left", "right",
            "up_left", "up_right", "down_left", "down_right"
        )) dpadPressed[n] = bmp("dpad_${n}_pressionado")
        buildControls()
    }

    private fun add(label: String, key: Int, ratio: Float) {
        controls += Control(label, key, bmp("botao_${label}_solto"), bmp("botao_${label}_pressionado"), ratio)
    }

    private fun buildControls() {
        when (system) {
            GameSystem.SNES -> {
                add("a", KeyEvent.KEYCODE_BUTTON_B, 0.19f)
                add("b", KeyEvent.KEYCODE_BUTTON_A, 0.19f)
                add("x", KeyEvent.KEYCODE_BUTTON_Y, 0.19f)
                add("y", KeyEvent.KEYCODE_BUTTON_X, 0.19f)
                add("l", KeyEvent.KEYCODE_BUTTON_L1, 0.32f)
                add("r", KeyEvent.KEYCODE_BUTTON_R1, 0.32f)
                add("select", KeyEvent.KEYCODE_BUTTON_SELECT, 0.28f)
                add("start", KeyEvent.KEYCODE_BUTTON_START, 0.28f)
            }
            GameSystem.MEGADRIVE -> {
                // Genesis Plus GX: A = RetroPad Y, B = RetroPad B, C = RetroPad A
                add("a", KeyEvent.KEYCODE_BUTTON_X, 0.19f)
                add("b", KeyEvent.KEYCODE_BUTTON_A, 0.19f)
                add("c", KeyEvent.KEYCODE_BUTTON_B, 0.19f)
                add("start", KeyEvent.KEYCODE_BUTTON_START, 0.28f)
            }
            GameSystem.NES -> {
                add("b", KeyEvent.KEYCODE_BUTTON_A, 0.2f)
                add("a", KeyEvent.KEYCODE_BUTTON_B, 0.2f)
                add("select", KeyEvent.KEYCODE_BUTTON_SELECT, 0.28f)
                add("start", KeyEvent.KEYCODE_BUTTON_START, 0.28f)
            }
        }
    }

    private fun place(c: Control, cx: Float, cy: Float, h: Float) {
        val w = c.widthRatio * h
        val ratio = c.released.height.toFloat() / c.released.width.toFloat()
        val hh = w * ratio
        c.rect.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val W = w.toFloat(); val H = h.toFloat()
        val m = H * 0.06f

        // D-Pad (esquerda embaixo)
        val ds = H * 0.5f
        dpadRect.set(m, H - m - ds, m + ds, H - m)

        val fb = H * 0.19f
        val o = fb * 0.80f
        val cx = W - m - fb - o
        val cy = H - m - fb / 2 - o

        fun find(n: String) = controls.firstOrNull { it.name == n }

        when (system) {
            GameSystem.SNES -> {
                find("a")?.let { place(it, cx + o, cy, H) }
                find("b")?.let { place(it, cx, cy + o, H) }
                find("x")?.let { place(it, cx, cy - o, H) }
                find("y")?.let { place(it, cx - o, cy, H) }
                find("l")?.let { place(it, m + it.widthRatio * H / 2, m + it.widthRatio * H / 4, H) }
                find("r")?.let { place(it, W - m - it.widthRatio * H / 2, m + it.widthRatio * H / 4, H) }
            }
            GameSystem.MEGADRIVE -> {
                val step = fb * 1.12f
                val bx = W - m - fb / 2 - step
                find("a")?.let { place(it, bx - step, cy + o * 0.4f, H) }
                find("b")?.let { place(it, bx, cy, H) }
                find("c")?.let { place(it, bx + step, cy - o * 0.4f, H) }
            }
            GameSystem.NES -> {
                val step = fb * 1.15f
                val bx = W - m - fb / 2 - step
                find("b")?.let { place(it, bx, cy + o * 0.5f, H) }
                find("a")?.let { place(it, bx + step, cy + o * 0.5f, H) }
            }
        }

        // Select / Start embaixo, no centro
        val sy = H - m - H * 0.06f
        find("select")?.let { place(it, W / 2 - it.widthRatio * H * 0.6f, sy, H) }
        find("start")?.let {
            val off = if (find("select") != null) it.widthRatio * H * 0.6f else 0f
            place(it, W / 2 + off, sy, H)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val idle = dpadMask == 0
        val bmp = if (idle) dpadIdle else dpadPressed[dpadName(dpadMask)] ?: dpadIdle
        canvas.drawBitmap(bmp, null, dpadRect, paint)
        for (c in controls) {
            canvas.drawBitmap(if (c in down) c.pressed else c.released, null, c.rect, paint)
        }
    }

    private fun dpadName(mask: Int): String {
        val v = if (mask and UP != 0) "up" else if (mask and DOWN != 0) "down" else ""
        val h = if (mask and LEFT != 0) "left" else if (mask and RIGHT != 0) "right" else ""
        return if (v.isNotEmpty() && h.isNotEmpty()) "${v}_$h" else v + h
    }

    private fun dpadDirection(x: Float, y: Float): Int {
        val grow = dpadRect.width() * 0.12f
        if (x < dpadRect.left - grow || x > dpadRect.right + grow ||
            y < dpadRect.top - grow || y > dpadRect.bottom + grow
        ) return 0
        val half = dpadRect.width() / 2
        val nx = (x - dpadRect.centerX()) / half
        val ny = (y - dpadRect.centerY()) / half
        val dead = 0.22f
        var h = abs(nx) > dead
        var v = abs(ny) > dead
        if (h && v) { // se um eixo domina bastante, ignora o outro (evita diagonal sem querer)
            if (abs(nx) > abs(ny) * 2.2f) v = false
            else if (abs(ny) > abs(nx) * 2.2f) h = false
        }
        var mask = 0
        if (h) mask = mask or if (nx < 0) LEFT else RIGHT
        if (v) mask = mask or if (ny < 0) UP else DOWN
        return mask
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val newDown = HashSet<Control>()
        var newMask = 0
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until ev.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == ev.actionIndex) continue
                val x = ev.getX(i); val y = ev.getY(i)
                val dir = dpadDirection(x, y)
                if (dir != 0) { newMask = newMask or dir; continue }
                // botão mais próximo cujo retângulo (um pouco maior) contém o toque
                var best: Control? = null
                var bestDist = Float.MAX_VALUE
                for (c in controls) {
                    val padX = c.rect.width() * 0.1f; val padY = c.rect.height() * 0.1f
                    if (x >= c.rect.left - padX && x <= c.rect.right + padX &&
                        y >= c.rect.top - padY && y <= c.rect.bottom + padY
                    ) {
                        val dx = x - c.rect.centerX(); val dy = y - c.rect.centerY()
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist) { bestDist = dist; best = c }
                    }
                }
                best?.let { newDown += it }
            }
        }

        var changed = false
        for (c in down) if (c !in newDown) { onKey(false, c.keyCode); changed = true }
        for (c in newDown) if (c !in down) {
            onKey(true, c.keyCode); changed = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        down.clear(); down.addAll(newDown)

        if (newMask != dpadMask) {
            val diff = newMask xor dpadMask
            val keys = listOf(
                UP to KeyEvent.KEYCODE_DPAD_UP, DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                LEFT to KeyEvent.KEYCODE_DPAD_LEFT, RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT
            )
            for ((bit, key) in keys) if (diff and bit != 0) onKey(newMask and bit != 0, key)
            if (newMask and dpadMask.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dpadMask = newMask
            changed = true
        }
        if (changed) invalidate()
        return true
    }
}
