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

    /** Card gerado (quando não há capa): degradê suave + nome do jogo. */
    fun generated(rom: File): Bitmap {
        val key = "g:" + rom.name
        cache.get(key)?.let { return it }
        val w = 480; val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (abs(rom.nameWithoutExtension.hashCode()) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.32f, 0.60f))
        val bottom = Color.HSVToColor(floatArrayOf(hue, 0.50f, 0.24f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(28, 255, 255, 255)
        c.drawCircle(w * 0.85f, h * 0.15f, w * 0.55f, p)
        c.drawCircle(w * 0.1f, h * 0.95f, w * 0.45f, p)

        val title = rom.nameWithoutExtension.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").trim().ifEmpty { rom.nameWithoutExtension }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 54f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, Color.argb(120, 0, 0, 0))
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, w - 80)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(40f, (h - layout.height) / 2f)
        layout.draw(c)
        c.restore()
        cache.put(key, bmp)
        return bmp
    }
}
