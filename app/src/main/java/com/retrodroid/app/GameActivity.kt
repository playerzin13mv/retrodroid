package com.retrodroid.app

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Date

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM = "rom_path"
        const val SLOTS = 6
    }

    private var retroView: GLRetroView? = null
    private var touchControls: ControlsOverlay? = null
    private lateinit var settings: Settings
    private lateinit var romFile: File
    private lateinit var saveDir: File
    private lateinit var sramFile: File

    private lateinit var fpsText: TextView
    private lateinit var recText: TextView
    private lateinit var dbgText: TextView
    private var gameSystem: GameSystem = GameSystem.NES
    private var frames = 0
    private var speedApplied = false
    private val ui = Handler(Looper.getMainLooper())

    private var menu: Dialog? = null
    private var menuBody: FrameLayout? = null
    private var recorder: VideoRecorder? = null
    private var d = 1f

    private fun dp(v: Int) = (v * d).toInt()

    // ===================================================================== ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        settings = Settings(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        romFile = File(intent.getStringExtra(EXTRA_ROM) ?: return fail("ROM não informada"))
        val system = RomDetector.detect(romFile) ?: return fail("Formato não suportado")

        saveDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(saveDir, romFile.nameWithoutExtension + ".srm")
        gameSystem = system

        val corePath = File(applicationInfo.nativeLibraryDir, system.coreLib)
        if (!corePath.exists()) return fail("Core não encontrado: ${system.coreLib}")

        val view = try {
            val data = GLRetroViewData(this).apply {
                coreFilePath = corePath.absolutePath
                gameFilePath = romFile.absolutePath
                systemDirectory = systemDir.absolutePath
                savesDirectory = saveDir.absolutePath
                saveRAMState = if (sramFile.exists()) sramFile.readBytes() else null
            }
            GLRetroView(this, data)
        } catch (e: Exception) {
            return fail("Erro ao iniciar: ${e.message}")
        }
        retroView = view
        lifecycle.addObserver(view)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val ov = try { ControlsOverlay(
            this, system, settings,
            onKey = { isDown, keyCode ->
                view.sendKeyEvent(if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
            },
            onLeftStick = { x, y -> view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, 0) },
            onRightStick = { x, y -> view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, 0) }
        ) } catch (e: Exception) {
            return fail("Erro nos controles: ${e.message}")
        }
        touchControls = ov
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // FPS (canto superior esquerdo)
        fpsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            text = "FPS: --"
            visibility = if (settings.showFps) View.VISIBLE else View.GONE
        }
        root.addView(fpsText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(60); topMargin = dp(8)
        })

        // diagnóstico dos botões (liga em Configurações Avançadas)
        dbgText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            text = "toque num botão…"
            visibility = if (settings.debugInput) View.VISIBLE else View.GONE
        }
        root.addView(dbgText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(60); topMargin = dp(40)
        })
        ov.onDebug = { msg -> if (settings.debugInput) dbgText.text = msg }

        // indicador de gravação
        recText = TextView(this).apply {
            text = "● REC"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        root.addView(recText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(60); topMargin = dp(8)
        })

        // botão de menu
        val menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.7f
            background = GradientDrawable().apply { setColor(Color.parseColor("#77000000")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { openMenu() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(48), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        setContentView(root)

        // contador de FPS real do emulador (quadros renderizados por segundo)
        lifecycleScope.launch {
            try {
                view.getGLRetroEvents().collect { ev ->
                    if (ev.javaClass.simpleName == "FrameRendered") {
                        frames++
                        // só mexe na velocidade depois que o core já está rodando (antes disso pode crashar)
                        if (!speedApplied) {
                            speedApplied = true
                            if (settings.speed != 1) view.frameSpeed = settings.speed
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openMenu()
        })
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            if (settings.showFps) fpsText.text = "FPS: $frames"
            frames = 0
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (retroView != null) ui.postDelayed(fpsTick, 1000)
    }

    override fun onPause() {
        ui.removeCallbacks(fpsTick)
        saveSram()
        if (recorder != null) stopRecording()
        super.onPause()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun saveSram() {
        try {
            val bytes = retroView?.serializeSRAM() ?: return
            if (bytes.isNotEmpty()) sramFile.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    // ===================================================================== menu
    private fun pauseGame() { retroView?.frameSpeed = 0 }
    private fun resumeGame() { retroView?.frameSpeed = settings.speed }

    private fun openMenu() {
        if (retroView == null || menu?.isShowing == true) return
        pauseGame()

        val body = FrameLayout(this)
        menuBody = body
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2181A20")); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(body)
        }

        val dlg = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(panel)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.55f)
            window?.setLayout(
                minOf((resources.displayMetrics.widthPixels * 0.9f).toInt(), dp(560)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnDismissListener { menu = null; resumeGame() }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    if (page != "main") { showMain(); true } else false
                } else false
            }
        }
        menu = dlg
        showMain()
        dlg.show()
        dlg.window?.let { WindowInsetsControllerCompat(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
    }

    private var page = "main"

    private fun setPage(name: String, content: View) {
        page = name
        menuBody?.removeAllViews()
        menuBody?.addView(content)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }

    private fun menuButton(text: String, accent: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (accent) "#3A3F4B" else "#2A2D35"))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor(if (accent) "#99FFFFFF" else "#44FFFFFF"))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    private fun showMain() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title("Menu"))
        col.addView(menuButton("Continuar", accent = true) { menu?.dismiss() })
        col.addView(menuButton("Save State") { showSlots(save = true) })
        col.addView(menuButton("Load State") { showSlots(save = false) })
        col.addView(menuButton(if (recorder == null) "Gravar vídeo" else "■ Parar gravação") {
            if (recorder == null) { beginRecording(); menu?.dismiss() } else { stopRecording(); showMain() }
        })
        col.addView(menuButton("Configurações Avançadas") { showAdvanced() })
        col.addView(menuButton("Sair do jogo") { menu?.dismiss(); finish() })
        setPage("main", ScrollView(this).apply {
            addView(col)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(330), (resources.displayMetrics.heightPixels * 0.82f).toInt()))
        })
    }

    // ----------------------------------------------------------------- slots
    private fun stateFile(n: Int) = File(saveDir, romFile.nameWithoutExtension + ".slot$n.state")
    private fun thumbFile(n: Int) = File(saveDir, romFile.nameWithoutExtension + ".slot$n.jpg")

    private fun showSlots(save: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(if (save) "Salvar em qual slot?" else "Carregar qual slot?"))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 1..SLOTS) row.addView(slotCard(n, save))
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        col.addView(menuButton("Voltar") { showMain() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })
        setPage("slots", col)
    }

    private fun slotCard(n: Int, save: Boolean): View {
        val has = stateFile(n).exists()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2D35")); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            setOnClickListener {
                if (save) saveToSlot(n) else if (has) loadFromSlot(n)
                else Toast.makeText(this@GameActivity, "Slot vazio", Toast.LENGTH_SHORT).show()
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#101216"))
            layoutParams = LinearLayout.LayoutParams(dp(154), dp(96))
        }
        if (has && thumbFile(n).exists()) {
            BitmapFactory.decodeFile(thumbFile(n).absolutePath)?.let { img.setImageBitmap(it) }
        }
        card.addView(img)
        card.addView(TextView(this).apply {
            text = "Slot $n"
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (has) DateFormat.format("dd/MM/yy HH:mm", Date(stateFile(n).lastModified())).toString() else "Vazio"
            setTextColor(Color.parseColor("#9AA0AA")); textSize = 12f
        })
        return card
    }

    /** Tira "print" só da imagem do jogo (SurfaceView), sem menus nem controles. */
    private fun captureGame(cb: (Bitmap?) -> Unit) {
        val v = retroView as? SurfaceView
        if (v == null || v.width <= 0 || v.height <= 0) { cb(null); return }
        val scale = minOf(1f, 480f / v.width)
        val bmp = Bitmap.createBitmap((v.width * scale).toInt().coerceAtLeast(2), (v.height * scale).toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(v, bmp, { r -> cb(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            cb(null)
        }
    }

    private fun saveToSlot(n: Int) {
        val rv = retroView ?: return
        captureGame { shot ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.Default) {
                    try {
                        val bytes = rv.serializeState()
                        stateFile(n).writeBytes(bytes)
                        if (shot != null) FileOutputStream(thumbFile(n)).use { shot.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        else thumbFile(n).delete()
                        true
                    } catch (e: Exception) { false }
                }
                Toast.makeText(this@GameActivity, if (ok) "Salvo no slot $n" else "Falha ao salvar", Toast.LENGTH_SHORT).show()
                if (menu?.isShowing == true) showSlots(save = true)
            }
        }
    }

    private fun loadFromSlot(n: Int) {
        val rv = retroView ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try { rv.unserializeState(stateFile(n).readBytes()); true } catch (e: Exception) { false }
            }
            Toast.makeText(this@GameActivity, if (ok) "Slot $n carregado" else "Falha ao carregar", Toast.LENGTH_SHORT).show()
            if (ok) menu?.dismiss()
        }
    }

    // ----------------------------------------------------------------- vídeo (com áudio)
    private val audioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askProjection() else startRecording(null, "Sem permissão de áudio: gravando só a imagem")
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            RecordService.start(applicationContext, res.resultCode, data) { projection ->
                runOnUiThread {
                    startRecording(projection, if (projection == null) "Não consegui capturar o áudio: gravando só a imagem" else null)
                }
            }
        } else {
            startRecording(null, "Captura de áudio negada: gravando só a imagem")
        }
    }

    private fun beginRecording() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askProjection()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askProjection() {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            startRecording(null, "Não consegui pedir a captura de áudio: gravando só a imagem")
        }
    }

    private fun startRecording(projection: MediaProjection?, note: String?) {
        val v = retroView as? SurfaceView ?: return
        val ov = touchControls ?: return
        val r = VideoRecorder(v, applicationContext, gameSystem, { ov.pressedIds }, projection)
        if (r.start()) {
            recorder = r
            recText.visibility = View.VISIBLE
            val msg = note ?: if (r.hasAudio) "Gravando com áudio… (menu → parar)" else "Gravando só a imagem… (menu → parar)"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else {
            RecordService.stop(applicationContext)
            Toast.makeText(this, "Não consegui iniciar a gravação", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recText.visibility = View.GONE
        Toast.makeText(this, "Salvando vídeo…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val ok = r.stopAndSave()
            RecordService.stop(app)
            ui.post {
                Toast.makeText(app, if (ok) "Vídeo salvo em Filmes/RetroDroid" else "Falha ao salvar o vídeo", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ----------------------------------------------------------------- configurações avançadas
    private fun showAdvanced() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title("Configurações Avançadas"))

        fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

        col.addView(switchRow("Mostrar FPS", settings.showFps) {
            settings.showFps = it
            fpsText.visibility = if (it) View.VISIBLE else View.GONE
        })
        col.addView(switchRow("Vibração ao tocar nos botões", settings.haptics) { settings.haptics = it })
        col.addView(switchRow("Diagnóstico dos botões (mostra a tecla enviada)", settings.debugInput) {
            settings.debugInput = it
            dbgText.visibility = if (it) View.VISIBLE else View.GONE
        })

        val opacityLabel = TextView(this).apply {
            text = "Opacidade dos controles: ${settings.overlayOpacity}%"
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(opacityLabel)
        col.addView(SeekBar(this).apply {
            max = 80
            progress = settings.overlayOpacity - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    settings.overlayOpacity = p + 20
                    opacityLabel.text = "Opacidade dos controles: ${p + 20}%"
                    touchControls?.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(TextView(this).apply {
            text = "Velocidade do jogo"
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, dp(12), 0, dp(6))
        })
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        fun paintChips() = chips.forEachIndexed { i, t ->
            val on = settings.speed == i + 1
            (t.background as GradientDrawable).setColor(Color.parseColor(if (on) "#4A5060" else "#2A2D35"))
            (t.background as GradientDrawable).setStroke(dp(1), Color.parseColor(if (on) "#CCFFFFFF" else "#44FFFFFF"))
        }
        for (s in 1..3) {
            val chip = TextView(this).apply {
                text = "${s}x"
                setTextColor(Color.WHITE); textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
                setOnClickListener { settings.speed = s; paintChips() }
            }
            chips += chip
            speedRow.addView(chip)
        }
        paintChips()
        col.addView(speedRow)

        col.addView(menuButton("Voltar") { showMain() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })
        setPage("advanced", ScrollView(this).apply {
            addView(col)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(330), (resources.displayMetrics.heightPixels * 0.82f).toInt()))
        })
    }

    // ===================================================================== controle físico
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (fromPad && event.keyCode != KeyEvent.KEYCODE_BACK && menu?.isShowing != true) {
            retroView?.sendKeyEvent(event.action, event.keyCode, 0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
