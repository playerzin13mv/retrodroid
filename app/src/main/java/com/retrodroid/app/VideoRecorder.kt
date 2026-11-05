package com.retrodroid.app

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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grava só a imagem do jogo (a SurfaceView do emulador): o menu nunca entra no vídeo.
 * Opcionais: mini-controle com os botões apertados, FPS e os controles da tela.
 * Áudio: som do jogo (captura de áudio interno do Android 10+), a voz do jogador (microfone), ou os dois;
 * com os dois, o som do jogo abaixa enquanto o jogador fala.
 * Tudo que acontece vai para o RecLog (relatório da gravação).
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
    private val controls: ControlsOverlay?,
    private val useGame: Boolean,
    private val useMic: Boolean,
    private val duckAmount: Float,      // 0 = não abaixa o jogo; 0.6 = abaixa 60% quando o jogador fala
    private val title: String
) {
    var hasAudio = false
        private set

    var audioError: String? = null
        private set

    @Volatile var heardSound = false
        private set

    @Volatile private var peak = 0

    val micEnabled: Boolean get() = useMic

    /** Maior volume captado desde a última chamada (0 a 32767). */
    fun takePeak(): Int {
        val p = peak
        peak = 0
        return p
    }

    @Volatile var audioSamples = 0
        private set

    @Volatile private var videoFrames = 0
    @Volatile private var audioQueued = 0

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
    private var gameRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val sampleRate = 48000

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
    private val written = HashMap<Int, Int>()

    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)
    private val pending = ArrayList<Pending>()

    private var vDrain: Thread? = null
    private var aDrain: Thread? = null
    @Volatile private var running = false

    private fun nowUs() = System.nanoTime() / 1000L

    fun start(): Boolean {
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0) { RecLog.add("ERRO: a tela do jogo ainda não tem tamanho"); return false }
        h = 720
        w = ((vw.toFloat() / vh * h).toInt() / 2) * 2
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tmpFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        RecLog.add("vídeo ${w}x$h; jogo=$useGame mic=$useMic duck=$duckAmount projeção=${projection != null}")

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
            RecLog.add("codificador de vídeo iniciado")

            if (useGame || useMic) {
                try {
                    setupAudio()
                    hasAudio = true
                    RecLog.add("áudio configurado")
                } catch (e: Exception) {
                    hasAudio = false
                    audioError = e.javaClass.simpleName + ": " + (e.message ?: "")
                    RecLog.add("ERRO no áudio: " + Log.getStackTraceString(e).take(700))
                    releaseAudio()
                }
            } else {
                RecLog.add("gravação sem áudio (escolha do jogador)")
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
            RecLog.add("ERRO ao iniciar a gravação: " + Log.getStackTraceString(e).take(700))
            running = false
            releaseAll()
            false
        }
    }

    // ------------------------------------------------------------ áudio
    private fun setupAudio() {
        if (useGame) {
            val proj = projection ?: throw IllegalStateException("sem permissão de captura de tela/áudio")
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
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
            RecLog.add("áudio do jogo: AudioRecord estado=${ar.state} (1 = ok)")
            if (ar.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord do jogo não iniciou")
            gameRecord = ar
        }
        if (useMic) {
            val minMic = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .let { if (it <= 0) 8192 else it }
            val mr = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minMic * 4)
            RecLog.add("microfone: AudioRecord estado=${mr.state} (1 = ok)")
            if (mr.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microfone não iniciou")
            micRecord = mr
        }
        if (gameRecord == null && micRecord == null) throw IllegalStateException("nenhuma fonte de áudio")

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

    private fun pcm(b: ByteArray, off: Int): Int =
        ((b[off + 1].toInt() shl 8) or (b[off].toInt() and 0xFF)).toShort().toInt()

    private fun appendMic(src: ByteArray, bytes: Int, q: ShortArray, count: Int): Int {
        var c = count
        var i = 0
        while (i + 1 < bytes && c < q.size) {
            q[c++] = ((src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)).toShort()
            i += 2
        }
        return c
    }

    private fun audioLoop() {
        val g = gameRecord
        val m = micRecord
        val ac = aCodec ?: return
        val out = ByteArray(4096)         // PCM 16 bits estéreo que vai pro AAC
        val gameBuf = ByteArray(4096)
        val micTmp = ByteArray(2048)
        val micQ = ShortArray(48000)
        var micCount = 0
        var duckGain = 1f
        var lastIn = 0L
        var audioBase = -1L        // instante (relógio do sistema) do primeiro bloco de áudio
        var framesQueued = 0L      // quantos quadros de áudio já foram para o codificador
        var reads = 0
        var lastLog = 0L
        var logPeak = 0
        try {
            g?.startRecording()
            m?.startRecording()
            RecLog.add("áudio: gravando (jogo=${g?.recordingState}, mic=${m?.recordingState}; 3 = gravando)")
            while (running) {
                var frames = 0
                if (g != null) {
                    val n = g.read(gameBuf, 0, gameBuf.size)
                    reads++
                    if (n > 0) {
                        frames = n / 4
                    } else {
                        if (n < 0 && audioError == null) {
                            audioError = "leitura do jogo falhou (código $n)"
                            RecLog.add("ERRO: " + audioError)
                        }
                        try { Thread.sleep(20) } catch (_: InterruptedException) {}
                    }
                    if (m != null) {
                        val mn = m.read(micTmp, 0, micTmp.size, AudioRecord.READ_NON_BLOCKING)
                        if (mn > 0) micCount = appendMic(micTmp, mn, micQ, micCount)
                    }
                } else if (m != null) {
                    val mn = m.read(micTmp, 0, micTmp.size)
                    reads++
                    if (mn > 0) {
                        micCount = appendMic(micTmp, mn, micQ, micCount)
                    } else {
                        if (mn < 0 && audioError == null) {
                            audioError = "leitura do microfone falhou (código $mn)"
                            RecLog.add("ERRO: " + audioError)
                        }
                        try { Thread.sleep(20) } catch (_: InterruptedException) {}
                    }
                    frames = minOf(micCount, out.size / 4)
                }
                if (micCount > 9600) {   // microfone atrasado mais de 200 ms: descarta o mais velho
                    val keep = 4800
                    System.arraycopy(micQ, micCount - keep, micQ, 0, keep)
                    micCount = keep
                }

                val outFrames = if (frames > 0) frames else 960
                val take = minOf(micCount, outFrames)

                // o jogo abaixa enquanto o jogador fala
                var micPeak = 0
                for (i in 0 until take) {
                    val a = if (micQ[i] < 0) -micQ[i].toInt() else micQ[i].toInt()
                    if (a > micPeak) micPeak = a
                }
                val target = if (duckAmount > 0f && g != null && m != null && micPeak > 1500) 1f - duckAmount else 1f
                duckGain += (target - duckGain) * (if (target < duckGain) 0.4f else 0.08f)

                var peakNow = 0
                for (i in 0 until outFrames) {
                    var l = 0
                    var r = 0
                    if (g != null && frames > 0) {
                        l = (pcm(gameBuf, 4 * i) * duckGain).toInt()
                        r = (pcm(gameBuf, 4 * i + 2) * duckGain).toInt()
                    }
                    if (i < take) {
                        val ms = (micQ[i] * 1.4f).toInt()
                        l += ms
                        r += ms
                    }
                    if (l > 32767) l = 32767 else if (l < -32768) l = -32768
                    if (r > 32767) r = 32767 else if (r < -32768) r = -32768
                    out[4 * i] = l.toByte()
                    out[4 * i + 1] = (l shr 8).toByte()
                    out[4 * i + 2] = r.toByte()
                    out[4 * i + 3] = (r shr 8).toByte()
                    val a = if (l < 0) -l else l
                    if (a > peakNow) peakNow = a
                }
                if (take > 0) {
                    System.arraycopy(micQ, take, micQ, 0, micCount - take)
                    micCount -= take
                }
                if (peakNow > peak) peak = peakNow
                if (peakNow > 300) heardSound = true
                if (peakNow > logPeak) logPeak = peakNow
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastLog > 1000) {
                    RecLog.add("áudio: pico=$logPeak leituras=$reads fila_mic=$micCount ganho_jogo=${"%.2f".format(Locale.US, duckGain)}")
                    lastLog = nowMs
                    logPeak = 0
                }

                val len = outFrames * 4

                // Carimbo de tempo do áudio. IMPORTANTE: tem que ser ABSOLUTO (mesmo relógio do vídeo),
                // porque o writeSample já desconta o baseUs de todas as faixas. (Antes eu descontava duas
                // vezes: todo o áudio ia parar no instante zero do arquivo e o vídeo ficava mudo.)
                val realStart = nowUs() - bytesToUs(len)
                if (audioBase < 0L) audioBase = realStart
                var expected = audioBase + framesQueued * 1_000_000L / sampleRate
                if (kotlin.math.abs(realStart - expected) > 80_000L) {   // desvio grande: realinha com o relógio
                    audioBase += realStart - expected
                    expected = realStart
                }
                var off = 0
                while (off < len && running) {
                    val idx = ac.dequeueInputBuffer(10_000)
                    if (idx < 0) continue
                    val ib = ac.getInputBuffer(idx) ?: break
                    ib.clear()
                    val chunk = minOf(ib.remaining(), len - off)
                    ib.put(out, off, chunk)
                    val pts = expected + bytesToUs(off)
                    ac.queueInputBuffer(idx, 0, chunk, pts, 0)
                    audioQueued++
                    lastIn = pts
                    off += chunk
                }
                framesQueued += outFrames
            }
            val idx = ac.dequeueInputBuffer(100_000)
            if (idx >= 0) ac.queueInputBuffer(idx, 0, 0, lastIn, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            try { g?.stop() } catch (_: Exception) {}
            try { m?.stop() } catch (_: Exception) {}
        } catch (e: Exception) {
            audioError = e.javaClass.simpleName + ": " + (e.message ?: "")
            RecLog.add("ERRO no laço de áudio: " + Log.getStackTraceString(e).take(700))
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
                    val cw = minOf(h * 0.42f, w * 0.6f)
                    ControllerHud.draw(c, system, pressed(), h * 0.03f, h - h * 0.03f - cw * 64f / 120f, cw)
                }
                if (showFps) {
                    fpsPaint.textSize = h * 0.05f
                    c.drawText("FPS: " + fps(), h * 0.03f, h * 0.08f, fpsPaint)
                }
            } finally {
                s.unlockCanvasAndPost(c)
                videoFrames++
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ muxer
    private fun addTrack(format: MediaFormat): Int = synchronized(muxLock) {
        val m = muxer ?: return -1
        val t = m.addTrack(format)
        addedTracks++
        RecLog.add("faixa adicionada ao arquivo: #$t (${format.getString(MediaFormat.KEY_MIME)}); $addedTracks de $expectedTracks")
        if (addedTracks >= expectedTracks) startMuxerLocked()
        t
    }

    private fun startMuxerLocked() {
        val m = muxer ?: return
        if (muxerStarted) return
        m.start()
        muxerStarted = true
        RecLog.add("arquivo de vídeo começou a ser escrito (faixas: $addedTracks)")
        for (p in pending) writeNow(p.track, p.data, p.pts, p.flags)
        pending.clear()
    }

    private fun writeNow(track: Int, data: ByteArray, pts: Long, flags: Int) {
        val bi = MediaCodec.BufferInfo()
        bi.set(0, data.size, pts, flags)
        muxer?.writeSampleData(track, ByteBuffer.wrap(data), bi)
        written[track] = (written[track] ?: 0) + 1
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
            if (!muxerStarted && addedTracks >= 1 && SystemClock.uptimeMillis() - startedAt > 5000) {
                RecLog.add("AVISO: passaram 5 s e falta uma faixa; seguindo só com as que existem")
                startMuxerLocked()
            }

            if (muxerStarted) {
                try { writeNow(track, arr, p, info.flags) } catch (_: Exception) {}
            } else if (pending.size < 900) {
                pending.add(Pending(track, arr, p, info.flags))
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
        } catch (e: Exception) {
            RecLog.add("ERRO no codificador de ${if (isAudio) "áudio" else "vídeo"}: " + e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    // ------------------------------------------------------------ parar e salvar
    /** Para a gravação e salva em Filmes/RetroDroid. Chame fora da thread principal. Retorna true se salvou. */
    fun stopAndSave(): Boolean {
        running = false
        try { handler.removeCallbacksAndMessages(null); captureThread.quitSafely() } catch (_: Exception) {}
        try { vCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { gameRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.stop() } catch (_: Exception) {}
        try { audioThread?.join(3000) } catch (_: Exception) {}
        try { vDrain?.join(4000) } catch (_: Exception) {}
        try { aDrain?.join(4000) } catch (_: Exception) {}
        synchronized(muxLock) { if (!muxerStarted && addedTracks >= 1) startMuxerLocked() }
        val ok = muxerStarted
        RecLog.add("fim: quadros de vídeo=$videoFrames, blocos de áudio enviados=$audioQueued, escritos por faixa=$written, ouviu som=$heardSound")
        releaseAll()
        if (!ok) {
            RecLog.add("ERRO: o arquivo nunca começou a ser escrito")
            tmpFile.delete()
            RecLog.flush(context)
            return false
        }
        val saved = saveToGallery(tmpFile)
        RecLog.add(if (saved) "vídeo salvo na galeria" else "ERRO ao salvar na galeria")
        RecLog.flush(context)
        return saved
    }

    private fun releaseAudio() {
        try { gameRecord?.release() } catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}
        try { aCodec?.stop() } catch (_: Exception) {}
        try { aCodec?.release() } catch (_: Exception) {}
        gameRecord = null; micRecord = null; aCodec = null
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

    private fun saveToGallery(file: File): Boolean {
        val uri = MediaSaver.saveVideo(context, file, "RetroDroid") ?: return false
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        RecordingStore.add(context, uri, "$title  •  $stamp")
        return true
    }
}
