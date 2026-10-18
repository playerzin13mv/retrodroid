package com.retrodroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Controles na tela, desenhados com os PNGs (drawable-nodpi) e posicionados por Layouts.kt.
 * As teclas seguem o NOME do botão do RetroPad (ver layout.py). Uma única View trata todos os dedos: dá pra apertar vários botões ao mesmo tempo e deslizar entre eles.
 *  - Nintendo 64 e 3DS: o analógico na tela. Os botões C do N64 mexem no analógico direito.
 *  - Toggle X / Toggle B: um toque segura o botão, o próximo toque solta.
 *  - DS e 3DS: toque fora dos botões = toque na tela (caneta).
 *  - Modo de edição: arraste os botões pra onde quiser (a posição fica salva por console).
 */
class ControlsOverlay(
    context: Context,
    private val system: GameSystem,
    private val settings: Settings,
    private val onKey: (down: Boolean, keyCode: Int) -> Unit,
    private val onLeftStick: (x: Float, y: Float) -> Unit,
    private val onRightStick: (x: Float, y: Float) -> Unit,
    private val onPointer: (x: Float, y: Float, down: Boolean) -> Unit
) : View(context) {

    private class Ctrl(
        val item: LayoutItem,
        val released: Bitmap,
        val pressed: Bitmap,
        val rect: RectF = RectF()
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val recPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val editLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xDDFFFFFF.toInt()
    }
    private val editFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }

    private val layoutPrefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    private fun bmp(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) throw IllegalStateException("Imagem não encontrada: $name")
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
    private val toggled = HashSet<Ctrl>()
    private var stickId = -1
    private var stickX = 0f
    private var stickY = 0f
    private var lastRight = Pair(0f, 0f)
    private var pointerDown = false

    /** ids dos botões apertados agora (sem o analógico); usado pelo controle que aparece no vídeo. */
    @Volatile var pressedIds: Set<String> = emptySet()
        private set

    /** Recebe uma frase tipo "▼ A → tecla 96" (diagnóstico). */
    var onDebug: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val keyDownAt = HashMap<Int, Long>()
    private val pendingRelease = HashMap<Int, Runnable>()

    var editMode: Boolean = false
        set(value) {
            field = value
            if (value) releaseEverything()
            invalidate()
        }

    private companion object {
        const val UP = 1; const val DOWN = 2; const val LEFT = 4; const val RIGHT = 8
        const val MIN_HOLD_MS = 60L   // toda tecla fica apertada pelo menos isso (toque rápido não se perde)
        const val STICK_RADIUS = 0.28f
    }

    init {
        for (item in Layouts.forSystem(system)) {
            // o D-Pad tem imagens próprias por direção, então não há "dpad_pressionado"
            val pressedName = if (item.kind == Kind.DPAD) item.tex + "_solto" else item.tex + "_pressionado"
            val c = Ctrl(item, bmp(item.tex + "_solto"), bmp(pressedName))
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

    // ------------------------------------------------------------ teclas (com tempo mínimo apertada)
    private fun pressKey(code: Int) {
        val pending = pendingRelease.remove(code)
        if (pending != null) {
            handler.removeCallbacks(pending)
            keyDownAt[code] = SystemClock.uptimeMillis()
            return
        }
        keyDownAt[code] = SystemClock.uptimeMillis()
        onKey(true, code)
    }

    private fun releaseKey(code: Int) {
        val elapsed = SystemClock.uptimeMillis() - (keyDownAt[code] ?: 0L)
        if (elapsed >= MIN_HOLD_MS) {
            keyDownAt.remove(code)
            onKey(false, code)
        } else {
            val r = Runnable {
                pendingRelease.remove(code)
                keyDownAt.remove(code)
                onKey(false, code)
            }
            pendingRelease[code] = r
            handler.postDelayed(r, MIN_HOLD_MS - elapsed)
        }
    }

    private fun releaseEverything() {
        for (c in down) if (c.item.kind == Kind.BUTTON) releaseKey(c.item.key)
        for (c in toggled) releaseKey(c.item.key)
        down.clear(); toggled.clear()
        if (dpadMask != 0) {
            if (dpadMask and UP != 0) releaseKey(KeyEvent.KEYCODE_DPAD_UP)
            if (dpadMask and DOWN != 0) releaseKey(KeyEvent.KEYCODE_DPAD_DOWN)
            if (dpadMask and LEFT != 0) releaseKey(KeyEvent.KEYCODE_DPAD_LEFT)
            if (dpadMask and RIGHT != 0) releaseKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            dpadMask = 0
        }
        if (stickX != 0f || stickY != 0f) { stickX = 0f; stickY = 0f; onLeftStick(0f, 0f) }
        stickId = -1
        if (lastRight != Pair(0f, 0f)) { lastRight = Pair(0f, 0f); onRightStick(0f, 0f) }
        if (pointerDown) { pointerDown = false; onPointer(0f, 0f, false) }
        updateHud()
    }

    // ------------------------------------------------------------ posições
    private fun allCtrls(): List<Ctrl> {
        val l = ArrayList<Ctrl>(ctrls)
        dpad?.let { l.add(it) }
        stick?.let { l.add(it) }
        return l
    }

    private fun posKey(c: Ctrl) = "${system.name}_${c.item.id}"

    private fun layoutAll() {
        if (width == 0 || height == 0) return
        val W = width.toFloat()
        val H = height.toFloat()
        for (c in allCtrls()) {
            val bw = c.item.size * H
            val bh = bw * c.released.height / c.released.width
            var cx: Float
            var cy: Float
            val parts = layoutPrefs.getString(posKey(c), null)?.split(",")
            val sx = parts?.getOrNull(0)?.toFloatOrNull()
            val sy = parts?.getOrNull(1)?.toFloatOrNull()
            if (sx != null && sy != null) {
                cx = sx * W; cy = sy * H
            } else {
                cx = when (c.item.anchor) {
                    'L' -> c.item.ox * H
                    'R' -> W - c.item.ox * H
                    else -> W / 2f + c.item.ox * H
                }
                cy = c.item.fy * H
            }
            c.rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        }
    }

    /** Volta todos os botões para a posição original. */
    fun resetLayout() {
        for (c in allCtrls()) layoutPrefs.edit().remove(posKey(c)).apply()
        layoutAll()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutAll()
    }

    // ------------------------------------------------------------ desenho
    private fun drawInto(canvas: Canvas, p: Paint) {
        dpad?.let { d ->
            val bmp = if (dpadMask == 0) d.released else dpadPressedBmp[dpadName(dpadMask)] ?: d.released
            canvas.drawBitmap(bmp, null, d.rect, p)
        }
        stick?.let { s ->
            canvas.drawBitmap(s.released, null, s.rect, p)
            val R = s.rect.width() * STICK_RADIUS
            val kw = s.rect.width() * 0.44f
            val kx = s.rect.centerX() + stickX * R
            val ky = s.rect.centerY() + stickY * R
            val knob = if (stickId != -1) knobPressed else knobIdle
            canvas.drawBitmap(knob, null, RectF(kx - kw / 2, ky - kw / 2, kx + kw / 2, ky + kw / 2), p)
        }
        for (c in ctrls) {
            val on = c in down || c in toggled
            canvas.drawBitmap(if (on) c.pressed else c.released, null, c.rect, p)
        }
    }

    override fun onDraw(canvas: Canvas) {
        paint.alpha = settings.overlayOpacity.coerceIn(20, 100) * 255 / 100
        drawInto(canvas, paint)
        if (editMode) {
            for (c in allCtrls()) {
                canvas.drawRoundRect(c.rect, 18f, 18f, editFill)
                canvas.drawRoundRect(c.rect, 18f, 18f, editLine)
            }
        }
    }

    /** Desenha os controles num vídeo de [outW] x [outH] (a tela inteira escalada). */
    fun drawForRecording(canvas: Canvas, outW: Float, outH: Float) {
        if (width == 0 || height == 0) return
        recPaint.alpha = settings.overlayOpacity.coerceIn(20, 100) * 255 / 100
        canvas.save()
        canvas.scale(outW / width, outH / height)
        drawInto(canvas, recPaint)
        canvas.restore()
    }

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
        if (h && v) {
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

    // ---- edição (arrastar botões)
    private var dragCtrl: Ctrl? = null
    private var dragDx = 0f
    private var dragDy = 0f

    private fun onEditTouch(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best: Ctrl? = null
                var bd = Float.MAX_VALUE
                for (c in allCtrls()) {
                    val grow = c.rect.width() * 0.08f
                    if (x >= c.rect.left - grow && x <= c.rect.right + grow &&
                        y >= c.rect.top - grow && y <= c.rect.bottom + grow
                    ) {
                        val dx = x - c.rect.centerX()
                        val dy = y - c.rect.centerY()
                        val dist = dx * dx + dy * dy
                        if (dist < bd) { bd = dist; best = c }
                    }
                }
                dragCtrl = best
                if (best != null) {
                    dragDx = x - best.rect.centerX()
                    dragDy = y - best.rect.centerY()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val c = dragCtrl
                if (c != null) {
                    val w = c.rect.width()
                    val h = c.rect.height()
                    val cx = (x - dragDx).coerceIn(w / 2, width - w / 2)
                    val cy = (y - dragDy).coerceIn(h / 2, height - h / 2)
                    c.rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val c = dragCtrl
                if (c != null) {
                    layoutPrefs.edit().putString(posKey(c), "${c.rect.centerX() / width},${c.rect.centerY() / height}").apply()
                }
                dragCtrl = null
            }
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return onEditTouch(ev)

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
        var sx = 0f
        var sy = 0f
        var touchX = -1f
        var touchY = -1f

        if (!ending) {
            for (i in 0 until ev.pointerCount) {
                if (i == upIdx) continue
                val x = ev.getX(i)
                val y = ev.getY(i)

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
                    val padX = c.rect.width() * 0.1f
                    val padY = c.rect.height() * 0.1f
                    if (x >= c.rect.left - padX && x <= c.rect.right + padX &&
                        y >= c.rect.top - padY && y <= c.rect.bottom + padY
                    ) {
                        val dx = x - c.rect.centerX()
                        val dy = y - c.rect.centerY()
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist) { bestDist = dist; best = c }
                    }
                }
                if (best != null) newDown += best
                else if (system.touchScreen && touchX < 0f) { touchX = x; touchY = y }
            }
        }

        var changed = false

        for (c in down) if (c !in newDown) {
            if (c.item.kind == Kind.BUTTON) releaseKey(c.item.key)
            if (c.item.kind != Kind.TOGGLE) onDebug?.invoke("▲ ${c.item.id.uppercase()}")
            changed = true
        }
        for (c in newDown) if (c !in down) {
            when (c.item.kind) {
                Kind.BUTTON -> pressKey(c.item.key)
                Kind.TOGGLE -> {
                    if (c in toggled) { toggled.remove(c); releaseKey(c.item.key) }
                    else { toggled.add(c); pressKey(c.item.key) }
                }
                else -> {}
            }
            onDebug?.invoke("▼ ${c.item.id.uppercase()} → " + when (c.item.kind) {
                Kind.BUTTON, Kind.TOGGLE -> "tecla ${c.item.key}"
                else -> "analógico direito"
            })
            buzz(); changed = true
        }
        down.clear(); down.addAll(newDown)

        var rx = 0f
        var ry = 0f
        for (c in down) when (c.item.id) {
            "c_up" -> ry -= 1f
            "c_down" -> ry += 1f
            "c_left" -> rx -= 1f
            "c_right" -> rx += 1f
        }
        if (Pair(rx, ry) != lastRight) { lastRight = Pair(rx, ry); onRightStick(rx, ry) }

        if (newMask != dpadMask) {
            val diff = newMask xor dpadMask
            val keys = listOf(
                UP to KeyEvent.KEYCODE_DPAD_UP, DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                LEFT to KeyEvent.KEYCODE_DPAD_LEFT, RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT
            )
            for ((bit, key) in keys) if (diff and bit != 0) { if (newMask and bit != 0) pressKey(key) else releaseKey(key) }
            onDebug?.invoke("D-Pad: " + (if (newMask == 0) "-" else dpadName(newMask)))
            if (newMask and dpadMask.inv() != 0) buzz()
            dpadMask = newMask
            changed = true
        }

        if (abs(sx - stickX) > 0.004f || abs(sy - stickY) > 0.004f) {
            if (stickX == 0f && stickY == 0f) buzz()
            stickX = sx; stickY = sy
            onLeftStick(sx, sy)
            changed = true
        }

        // toque na tela (DS / 3DS)
        if (system.touchScreen) {
            if (touchX >= 0f && width > 0 && height > 0) {
                onPointer(touchX / width, touchY / height, true)
                pointerDown = true
            } else if (pointerDown) {
                onPointer(0f, 0f, false)
                pointerDown = false
            }
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
        val ids = HashSet<String>()
        if (dpadMask and UP != 0) ids += "up"
        if (dpadMask and DOWN != 0) ids += "down"
        if (dpadMask and LEFT != 0) ids += "left"
        if (dpadMask and RIGHT != 0) ids += "right"
        for (c in down) if (c.item.kind != Kind.TOGGLE) ids += c.item.id
        for (c in toggled) ids += c.item.id.removePrefix("toggle_")
        pressedIds = ids
    }
}
