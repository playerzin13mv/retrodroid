package com.retrodroid.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** Desenha o mini-controle (um modelo por console) que aparece no canto do vídeo; botão apertado = branco. */
object ControllerHud {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE1202226.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val directions = setOf("up", "down", "left", "right")

    /** [left]/[top] = canto do controle em pixels; [width] = largura em pixels (altura = width * 64/120). */
    fun draw(c: Canvas, system: GameSystem, pressed: Set<String>, left: Float, top: Float, width: Float) {
        val saved = c.save()
        c.translate(left, top)
        val s = width / 120f
        c.scale(s, s)

        for (b in HudLayouts.bodies(system)) {
            rect.set(b.x, b.y, b.x + b.w, b.y + b.h)
            c.drawRoundRect(rect, b.r, b.r, bodyPaint)
        }
        for (e in HudLayouts.elements(system)) {
            val on = e.id in pressed
            fillPaint.color = if (on) 0xFFFFFFFF.toInt() else 0xFF4A4F58.toInt()
            rect.set(e.x, e.y, e.x + e.w, e.y + e.h)
            if (e.shape == 'c') {
                c.drawOval(rect, fillPaint)
            } else {
                val r = min(e.w, e.h) * (if (e.id in directions) 0.12f else 0.3f)
                c.drawRoundRect(rect, r, r, fillPaint)
            }
            if (e.label.isNotEmpty()) {
                textPaint.textSize = min(e.w, e.h) * 0.62f
                textPaint.color = if (on) 0xFF141414.toInt() else 0xFFD7D7D7.toInt()
                val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                c.drawText(e.label, rect.centerX(), cy, textPaint)
            }
        }
        c.restoreToCount(saved)
    }
}
