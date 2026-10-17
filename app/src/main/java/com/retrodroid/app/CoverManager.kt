package com.retrodroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Capas dos jogos.
 * 1) capa escolhida por você (galeria) ou baixada do libretro-thumbnails (pelo nome do arquivo da ROM);
 * 2) se não houver, gera um card bonito com o nome do jogo.
 * Obs.: ROMs de SNES, Mega Drive, NES e N64 não têm ícone embutido (diferente de DS/3DS),
 * por isso o ícone vem da internet (pelo nome) ou de uma imagem que você escolher.
 */
class CoverManager(private val context: Context) {

    private val dir = File(context.filesDir, "covers").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(16)

    fun coverFile(rom: File) = File(dir, rom.nameWithoutExtension + ".png")
    private fun triedFile(rom: File) = File(dir, rom.nameWithoutExtension + ".none")

    fun hasCover(rom: File) = coverFile(rom).exists()
    fun wasTried(rom: File) = triedFile(rom).exists()

    fun load(rom: File): Bitmap? {
        val f = coverFile(rom)
        if (!f.exists()) return null
        val key = "c:" + f.absolutePath + f.lastModified()
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun clear(rom: File) {
        coverFile(rom).delete()
        triedFile(rom).delete()
    }

    /** Usa uma imagem da galeria como capa. */
    fun setCustom(rom: File, uri: Uri): Boolean = try {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) false else {
            val max = 700f
            val scale = minOf(1f, max / maxOf(bmp.width, bmp.height))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            coverFile(rom).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            triedFile(rom).delete()
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Tenta baixar a capa pelo nome da ROM (nomes no padrão No-Intro funcionam melhor). Chamar fora da thread principal. */
    fun fetchOnline(rom: File, system: GameSystem): Boolean {
        val name = rom.nameWithoutExtension.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
        for (kind in listOf("Named_Boxarts", "Named_Titles")) {
            try {
                val url = URL("https://thumbnails.libretro.com/${enc(system.thumbDir)}/$kind/${enc(name)}.png")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                if (conn.responseCode == 200) {
                    conn.inputStream.use { input -> coverFile(rom).outputStream().use { input.copyTo(it) } }
                    triedFile(rom).delete()
                    return true
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
        triedFile(rom).writeText("x")
        return false
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Cor de destaque do jogo: média da capa (se houver) ou derivada do nome; sempre bem vibrante. */
    fun accent(rom: File, title: String): Int {
        val cover = load(rom)
        val base = if (cover != null) {
            try { Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0) } catch (e: Exception) { hueColor(title, 0.7f, 0.95f) }
        } else hueColor(title, 0.7f, 0.95f)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 1f)
        hsv[2] = hsv[2].coerceIn(0.85f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun hueColor(title: String, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf((abs(title.hashCode()) % 360).toFloat(), s, v))

    /** Card gerado (quando não há capa): degradê vibrante + nome do jogo + marca d'água do console. */
    fun generated(rom: File, title: String, system: GameSystem?): Bitmap {
        val key = "g:" + rom.name + "|" + title
        cache.get(key)?.let { return it }
        val w = 480; val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (abs(title.hashCode()) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.60f, 1.00f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 28f) % 360f, 0.85f, 0.42f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(40, 255, 255, 255)
        c.drawCircle(w * 0.9f, h * 0.12f, w * 0.60f, p)
        c.drawCircle(w * 0.05f, h * 0.98f, w * 0.50f, p)
        p.color = Color.argb(26, 255, 255, 255)
        c.drawCircle(w * 0.5f, h * 0.55f, w * 0.30f, p)

        // marca d'água do console
        if (system != null) {
            val wm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(46, 255, 255, 255); textSize = 150f; textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            c.drawText(system.badge, w / 2f, h * 0.92f, wm)
        }

        val clean = title.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").trim().ifEmpty { title }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 56f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(10f, 0f, 4f, Color.argb(140, 0, 0, 0))
        }
        val layout = StaticLayout.Builder.obtain(clean, 0, clean.length, tp, w - 80)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(40f, (h - layout.height) / 2f - 20f)
        layout.draw(c)
        c.restore()
        cache.put(key, bmp)
        return bmp
    }
}
