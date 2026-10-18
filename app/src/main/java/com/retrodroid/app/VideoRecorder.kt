package com.retrodroid.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import java.io.File
import java.nio.ByteBuffer

/**
 * Grava só a imagem do jogo (a SurfaceView do emulador): os controles na tela NÃO entram no vídeo.
 * No canto inferior esquerdo desenha um mini-controle do console, e o botão apertado fica branco.
 * Se [projection] existir, grava também o áudio do jogo (captura de áudio interno do Android 10+).
 */
class VideoRecorder(
    private val view: SurfaceView,
    private val context: Context,
    private val system: GameSystem,
    private val pressed: () -> Set<String>,
    private val projection: MediaProjection?,
    private val showInputs: Boolean,
    private val showFps: Boolean,
    private val showControls: Boolean,
    private val fps: () -> Int,
    private val controls: ControlsOverlay?
) {
    var hasAudio = false
        private set

    /** Motivo da falha do áudio (para mostrar ao jogador). */
    var audioError: String? = null
        private set

    /** Quantos pedaços de áudio entraram no vídeo (0 = ficou mudo). */
    @Volatile var audioSamples = 0
        private set

    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    // vídeo
    private var vCodec: MediaCodec? = null
    private var surface: Surface? = null
    private val captureThread = HandlerThread("rec-capture")
    private lateinit var handler: Handler
    private lateinit var frame: Bitmap
    private var w = 0
    private var h = 0

    // áudio
    private var aCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val sampleRate = 44100

    // saída
    private var muxer: MediaMuxer? = null
    private lateinit var tmpFile: File
    private val muxLock = Any()
    private var muxerStarted = false
    private var expectedTracks = 1
    private var addedTracks = 0
    private var baseUs = 0L
    private var startedAt = 0L
    private val lastPts = HashMap<Int, Long>()

    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)
    private val pending = ArrayList<Pending>()

    private var vDrain: Thread? = null
    private var aDrain: Thread? = null
    @Volatile private var running = false

    private fun nowUs() = System.nanoTime() / 1000L

    fun start(): Boolean {
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0) return false
        h = 720
        w = ((vw.toFloat() / vh * h).toInt() / 2) * 2
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tmpFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")

        return try {
            baseUs = nowUs()
            startedAt = SystemClock.uptimeMillis()

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = vc.createInputSurface()
            vc.start()
            vCodec = vc

            if (projection != null) {
                try { setupAudio(); hasAudio = true } catch (e: Exception) { hasAudio = false; audioError = e.javaClass.simpleName + ": " + (e.message ?: ""); releaseAudio() }
            }
            expectedTracks = if (hasAudio) 2 else 1
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running = true
            vDrain = Thread { drain(vc, false) }.also { it.start() }
            if (hasAudio) {
                val ac = aCodec!!
                aDrain = Thread { drain(ac, true) }.also { it.start() }
                audioThread = Thread { audioLoop() }.also { it.start() }
            }
            captureThread.start()
            handler = Handler(captureThread.looper)
            handler.post(captureTick)
            true
        } catch (e: Exception) {
            running = false
            releaseAll()
            false
        }
    }

    // ------------------------------------------------------------ áudio
    private fun setupAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val af = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .let { if (it <= 0) 16384 else it }
        val ar = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(cfg)
            .setAudioFormat(af)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        if (ar.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord não iniciou")
        audioRecord = ar

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        ac.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.start()
        aCodec = ac
    }

    private fun bytesToUs(bytes: Int): Long = bytes / 4L * 1_000_000L / sampleRate

    private fun audioLoop() {
        val ar = audioRecord ?: return
        val ac = aCodec ?: return
        val buf = ByteArray(4096)
        var lastIn = 0L
        try {
            ar.startRecording()
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                var off = 0
                while (off < n && running) {
                    val idx = ac.dequeueInputBuffer(10_000)
                    if (idx < 0) continue
                    val ib = ac.getInputBuffer(idx) ?: break
                    ib.clear()
                    val chunk = minOf(ib.remaining(), n - off)
                    ib.put(buf, off, chunk)
                    val pts = (nowUs() - baseUs - bytesToUs(n - off)).coerceAtLeast(0L)
                    ac.queueInputBuffer(idx, 0, chunk, pts, 0)
                    lastIn = pts
                    off += chunk
                }
            }
            val idx = ac.dequeueInputBuffer(100_000)
            if (idx >= 0) ac.queueInputBuffer(idx, 0, 0, lastIn, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            ar.stop()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ vídeo (captura da SurfaceView)
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
                if (showControls) controls?.drawForRecording(c, w.toFloat(), h.toFloat())
                if (showInputs) {
                    val cw = h * 0.42f
                    ControllerHud.draw(c, system, pressed(), h * 0.03f, h - h * 0.03f - cw * 64f / 120f, cw)
                }
                if (showFps) {
                    fpsPaint.textSize = h * 0.05f
                    c.drawText("FPS: " + fps(), h * 0.03f, h * 0.08f, fpsPaint)
                }
            } finally {
                s.unlockCanvasAndPost(c)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ muxer
    private fun addTrack(format: MediaFormat): Int = synchronized(muxLock) {
        val m = muxer ?: return -1
        val t = m.addTrack(format)
        addedTracks++
        if (addedTracks >= expectedTracks) startMuxerLocked()
        t
    }

    private fun startMuxerLocked() {
        val m = muxer ?: return
        if (muxerStarted) return
        m.start()
        muxerStarted = true
        for (p in pending) writeNow(p.track, p.data, p.pts, p.flags)
        pending.clear()
    }

    private fun writeNow(track: Int, data: ByteArray, pts: Long, flags: Int) {
        val bi = MediaCodec.BufferInfo()
        bi.set(0, data.size, pts, flags)
        muxer?.writeSampleData(track, ByteBuffer.wrap(data), bi)
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
      synchronized(muxLock) {
        var p = (info.presentationTimeUs - baseUs).coerceAtLeast(0L)
        val last = lastPts[track] ?: -1L
        if (p <= last) p = last + 1
        lastPts[track] = p

        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val arr = ByteArray(info.size)
        buf.get(arr)

        // se o áudio demorar demais pra começar, segue só com o que já existe
        if (!muxerStarted && addedTracks >= 1 && SystemClock.uptimeMillis() - startedAt > 5000) startMuxerLocked()

        if (muxerStarted) {
            try { writeNow(track, arr, p, info.flags) } catch (_: Exception) {}
        } else if (pending.size < 900) {
            pending.add(Pending(track, arr, p, info.flags))
        } else {
            // fila cheia: descarta
        }
      }
    }

    private fun drain(codec: MediaCodec, isAudio: Boolean) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = addTrack(codec.outputFormat)
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && track >= 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        writeSample(track, buf, info)
                        if (isAudio) audioSamples++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ parar e salvar
    /** Para a gravação e salva em Filmes/RetroDroid. Chame fora da thread principal. Retorna true se salvou. */
    fun stopAndSave(): Boolean {
        running = false
        try { handler.removeCallbacksAndMessages(null); captureThread.quitSafely() } catch (_: Exception) {}
        try { vCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioThread?.join(3000) } catch (_: Exception) {}
        try { vDrain?.join(4000) } catch (_: Exception) {}
        try { aDrain?.join(4000) } catch (_: Exception) {}
        synchronized(muxLock) {
            if (!muxerStarted && addedTracks >= 1) startMuxerLocked() else Unit
        }
        val ok = muxerStarted
        releaseAll()
        if (!ok) { tmpFile.delete(); return false }
        return saveToGallery(tmpFile)
    }

    private fun releaseAudio() {
        try { audioRecord?.release() } catch (_: Exception) {}
        try { aCodec?.stop() } catch (_: Exception) {}
        try { aCodec?.release() } catch (_: Exception) {}
        audioRecord = null; aCodec = null
    }

    private fun releaseAll() {
        releaseAudio()
        try { vCodec?.stop() } catch (_: Exception) {}
        try { vCodec?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        vCodec = null; muxer = null; surface = null
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
