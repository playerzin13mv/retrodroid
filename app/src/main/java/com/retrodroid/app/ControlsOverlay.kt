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
import kotlin.math.hypot

/**
 * Controles na tela, desenhados com os PNGs (drawable-nodpi) e posicionados por Layouts.kt.
 * Uma única View trata todos os dedos: dá pra apertar vários botões ao mesmo tempo
 * e deslizar o dedo entre eles. No Nintendo 64 o D-Pad vira um analógico e os botões C
 * mexem no analógico direito.
 *
 * Mapeamento do LibretroDroid (teclas do Android -> RetroPad):
 *   BUTTON_A -> B (baixo) | BUTTON_B -> A (direita) | BUTTON_X -> Y (esquerda) | BUTTON_Y -> X (cima)
 */
class ControlsOverlay(
    context: Context,
    system: GameSystem,
    private val settings: Settings,
    private val onKey: (down: Boolean, keyCode: Int) -> Unit,
    private val onLeftStick: (x: Float, y: Float) -> Unit,
    private val onRightStick: (x: Float, y: Float) -> Unit
) : View(context) {

    private class Ctrl(
        val item: LayoutItem,
        val released: Bitmap,
        val pressed: Bitmap,
        val rect: RectF = RectF()
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun bmp(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        return BitmapFactory.decodeResource(resources, id)
    }

    private val ctrls = ArrayList<Ctrl>()
    private var dpad: Ctrl? = null
    private var stick: Ctrl? = null
    private lateinit var knobIdle: Bitmap
    private lateinit var knobPressed: Bitmap
    private val dpadPressedBmp = HashMap<String, Bitmap>()

    private var dpadMask = 0
    private val down = HashSet<Ctrl>()
    private var stickId = -1
    private var stickX = 0f   // -1..1
    private var stickY = 0f
    private var lastRight = Pair(0f, 0f)

    /** Cópia (thread-safe) dos botões apertados agora; usada pelo gravador de vídeo. */
    @Volatile var hudSnapshot: List<Bitmap> = emptyList()
        private set

    private companion object {
        const val UP = 1; const val DOWN = 2; const val LEFT = 4; const val RIGHT = 8
    }

    init {
        for (item in Layouts.forSystem(system)) {
            val c = Ctrl(item, bmp(item.tex + "_solto"), bmp(item.tex + "_pressionado"))
            when (item.kind) {
                Kind.DPAD -> dpad = c
                Kind.STICK -> stick = c
                else -> ctrls += c
            }
        }
        if (dpad != null) {
            for (n in listOf("up", "down", "left", "right", "up_left", "up_right", "down_left", "down_right"))
                dpadPressedBmp[n] = bmp("dpad_${n}_pressionado")
        }
        if (stick != null) {
            knobIdle = bmp("stick_knob_solto")
            knobPressed = bmp("stick_knob_pressionado")
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val W = w.toFloat(); val H = h.toFloat()
        fun place(c: Ctrl) {
            val it = c.item
            val cx = when (it.anchor) {
                'L' -> it.ox * H
                'R' -> W - it.ox * H
                else -> W / 2f + it.ox * H
            }
            val cy = it.fy * H
            val bw = it.size * H
            val bh = bw * c.released.height / c.released.width
            c.rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        }
        ctrls.forEach { place(it) }
        dpad?.let { place(it) }
        stick?.let { place(it) }
    }

    // ------------------------------------------------------------ desenho
    override fun onDraw(canvas: Canvas) {
        paint.alpha = settings.overlayOpacity.coerceIn(20, 100) * 255 / 100
        dpad?.let { d ->
            val bmp = if (dpadMask == 0) d.released else dpadPressedBmp[dpadName(dpadMask)] ?: d.released
            canvas.drawBitmap(bmp, null, d.rect, paint)
        }
        stick?.let { s ->
            canvas.drawBitmap(s.released, null, s.rect, paint)
            val R = s.rect.width() * STICK_RADIUS
            val kw = s.rect.width() * 0.44f
            val kx = s.rect.centerX() + stickX * R
            val ky = s.rect.centerY() + stickY * R
            val knob = if (stickId != -1) knobPressed else knobIdle
            canvas.drawBitmap(knob, null, RectF(kx - kw / 2, ky - kw / 2, kx + kw / 2, ky + kw / 2), paint)
        }
        for (c in ctrls) canvas.drawBitmap(if (c in down) c.pressed else c.released, null, c.rect, paint)
    }

    private val STICK_RADIUS = 0.28f

    private fun dpadName(mask: Int): String {
        val v = if (mask and UP != 0) "up" else if (mask and DOWN != 0) "down" else ""
        val h = if (mask and LEFT != 0) "left" else if (mask and RIGHT != 0) "right" else ""
        return if (v.isNotEmpty() && h.isNotEmpty()) "${v}_$h" else v + h
    }

    // ------------------------------------------------------------ toque
    private fun dpadDirection(x: Float, y: Float): Int {
        val d = dpad ?: return 0
        val grow = d.rect.width() * 0.12f
        if (x < d.rect.left - grow || x > d.rect.right + grow ||
            y < d.rect.top - grow || y > d.rect.bottom + grow
        ) return 0
        val half = d.rect.width() / 2
        val nx = (x - d.rect.centerX()) / half
        val ny = (y - d.rect.centerY()) / half
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

    private fun inStick(x: Float, y: Float): Boolean {
        val s = stick ?: return false
        val r = s.rect.width() / 2 * 1.1f
        return hypot(x - s.rect.centerX(), y - s.rect.centerY()) <= r
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        val ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val upIdx = if (action == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1

        if (ending) stickId = -1
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = ev.actionIndex
            if (stick != null && stickId == -1 && inStick(ev.getX(i), ev.getY(i))) stickId = ev.getPointerId(i)
        }
        if (upIdx >= 0 && ev.getPointerId(upIdx) == stickId) stickId = -1

        val newDown = HashSet<Ctrl>()
        var newMask = 0
        var sx = 0f; var sy = 0f

        if (!ending) {
            for (i in 0 until ev.pointerCount) {
                if (i == upIdx) continue
                val x = ev.getX(i); val y = ev.getY(i)

                if (ev.getPointerId(i) == stickId) {
                    val s = stick!!
                    val R = s.rect.width() * STICK_RADIUS
                    var dx = (x - s.rect.centerX()) / R
                    var dy = (y - s.rect.centerY()) / R
                    val len = hypot(dx, dy)
                    if (len > 1f) { dx /= len; dy /= len }
                    if (len < 0.08f) { dx = 0f; dy = 0f }
                    sx = dx; sy = dy
                    continue
                }

                val dir = dpadDirection(x, y)
                if (dir != 0) { newMask = newMask or dir; continue }

                var best: Ctrl? = null
                var bestDist = Float.MAX_VALUE
                for (c in ctrls) {
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

        // botões (teclas) e botões C (analógico direito)
        for (c in down) if (c !in newDown) {
            if (c.item.kind == Kind.BUTTON) onKey(false, c.item.key)
            changed = true
        }
        for (c in newDown) if (c !in down) {
            if (c.item.kind == Kind.BUTTON) onKey(true, c.item.key)
            buzz(); changed = true
        }
        down.clear(); down.addAll(newDown)

        var rx = 0f; var ry = 0f
        for (c in down) when (c.item.id) {
            "c_up" -> ry -= 1f
            "c_down" -> ry += 1f
            "c_left" -> rx -= 1f
            "c_right" -> rx += 1f
        }
        if (Pair(rx, ry) != lastRight) { lastRight = Pair(rx, ry); onRightStick(rx, ry) }

        // D-Pad
        if (newMask != dpadMask) {
            val diff = newMask xor dpadMask
            val keys = listOf(
                UP to KeyEvent.KEYCODE_DPAD_UP, DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                LEFT to KeyEvent.KEYCODE_DPAD_LEFT, RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT
            )
            for ((bit, key) in keys) if (diff and bit != 0) onKey(newMask and bit != 0, key)
            if (newMask and dpadMask.inv() != 0) buzz()
            dpadMask = newMask
            changed = true
        }

        // Analógico
        if (abs(sx - stickX) > 0.004f || abs(sy - stickY) > 0.004f) {
            if (stickX == 0f && stickY == 0f) buzz()
            stickX = sx; stickY = sy
            onLeftStick(sx, sy)
            changed = true
        }

        if (changed) {
            updateHud()
            invalidate()
        }
        return true
    }

    private fun buzz() {
        if (settings.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun updateHud() {
        val list = ArrayList<Bitmap>()
        if (dpadMask != 0) dpadPressedBmp[dpadName(dpadMask)]?.let { list += it }
        if (stickId != -1) list += knobPressed
        for (c in down) list += c.pressed
        hudSnapshot = list
    }
}
