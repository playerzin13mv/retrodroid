package com.retrodroid.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Fundo animado do Launcher: manchas de luz que flutuam e mudam de cor conforme o jogo selecionado. */
class AuroraView(context: Context) : View(context) {

    private var accent = Color.parseColor("#6C63FF")
    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()
    private var colorAnim: ValueAnimator? = null

    private val spin = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 40_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    fun setAccent(target: Int) {
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(evaluator, accent, target).apply {
            duration = 700
            addUpdateListener { accent = it.animatedValue as Int }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun shift(color: Int, deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = evaluator.evaluate(0.70f, accent, Color.parseColor("#0A0C12")) as Int
        paint.shader = LinearGradient(0f, 0f, 0f, h, top, Color.parseColor("#05060A"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)

        val r = min(w, h) * 0.95f
        for (i in 0..2) {
            val a = t * (1f + i * 0.5f) + i * 2.1f
            val cx = w * (0.5f + 0.42f * cos(a))
            val cy = h * (0.45f + 0.30f * sin(a * 0.8f + i))
            val col = shift(accent, i * 40f - 40f)
            paint.shader = RadialGradient(
                cx, cy, r,
                Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
    }
}
