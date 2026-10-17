package com.retrodroid.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import java.io.File

/**
 * Grava só a imagem do jogo (a SurfaceView do emulador), então os controles na tela NÃO entram no vídeo.
 * No canto inferior esquerdo do vídeo desenha pequenos ícones dos botões que o jogador está apertando.
 * Ainda sem áudio do jogo.
 */
class VideoRecorder(
    private val view: SurfaceView,
    private val context: Context,
    private val hud: () -> List<Bitmap>
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var track = -1
    @Volatile private var muxerStarted = false
    @Volatile private var running = false

    private var drainThread: Thread? = null
    private val captureThread = HandlerThread("rec-capture")
    private lateinit var handler: Handler
    private lateinit var frame: Bitmap
    private lateinit var tmpFile: File
    private var w = 0
    private var h = 0
    private val hudPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 215 }

    fun start(): Boolean {
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0) return false
        h = 720
        w = ((vw.toFloat() / vh * h).toInt() / 2) * 2
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tmpFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")

        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            codec = c
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            running = true
            drainThread = Thread { drain() }.also { it.start() }
            captureThread.start()
            handler = Handler(captureThread.looper)
            handler.post(captureTick)
            true
        } catch (e: Exception) {
            running = false
            release()
            false
        }
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = SystemClock.uptimeMillis()
            try {
                PixelCopy.request(view, frame, { result ->
                    if (result == PixelCopy.SUCCESS && running) drawFrame()
                    if (running) {
                        val wait = (33 - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(0)
                        handler.postDelayed(this, wait)
                    }
                }, handler)
            } catch (e: Exception) {
                if (running) handler.postDelayed(this, 33)
            }
        }
    }

    private fun drawFrame() {
        val s = surface ?: return
        try {
            val c: Canvas = s.lockHardwareCanvas()
            try {
                c.drawBitmap(frame, 0f, 0f, null)
                drawHud(c)
            } finally {
                s.unlockCanvasAndPost(c)
            }
        } catch (_: Exception) {
        }
    }

    /** Ícones pequenos dos botões apertados, no canto inferior esquerdo. */
    private fun drawHud(c: Canvas) {
        val list = hud()
        if (list.isEmpty()) return
        val size = h * 0.085f
        val gap = h * 0.012f
        var x = h * 0.03f
        val y = h - size - h * 0.03f
        for (b in list) {
            val bw = size * b.width / b.height
            c.drawBitmap(b, null, RectF(x, y, x + bw, y + size), hudPaint)
            x += bw + gap
        }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer!!.addTrack(c.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                } else if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Para a gravação e salva em Filmes/RetroDroid. Chame fora da thread principal. Retorna true se salvou. */
    fun stopAndSave(): Boolean {
        running = false
        try { handler.removeCallbacksAndMessages(null); captureThread.quitSafely() } catch (_: Exception) {}
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { drainThread?.join(4000) } catch (_: Exception) {}
        val ok = muxerStarted
        release()
        if (!ok) { tmpFile.delete(); return false }
        return saveToGallery(tmpFile)
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        codec = null; muxer = null; surface = null
    }

    private fun saveToGallery(file: File): Boolean = try {
        val name = "RetroDroid_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RetroDroid")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
            true
        }
    } catch (e: Exception) {
        false
    }
}
