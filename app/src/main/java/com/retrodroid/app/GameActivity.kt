package com.retrodroid.app

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import android.view.Window
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var gameSystem: GameSystem = GameSystem.NES

    private lateinit var fpsText: TextView
    private lateinit var recText: TextView
    private lateinit var dbgText: TextView
    private lateinit var menuBtn: TextView
    private lateinit var editBar: LinearLayout
    private var frames = 0
    private var lastFps = 0
    private var speedApplied = false
    private var shaderFailed = false
    private val ui = Handler(Looper.getMainLooper())

    private var menu: Dialog? = null
    private var menuBody: FrameLayout? = null
    private var page = "main"
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

        romFile = File(intent.getStringExtra(EXTRA_ROM) ?: return fail(tr("ROM não informada")))
        val system = RomDetector.detect(romFile) ?: return fail(tr("Formato não suportado"))
        gameSystem = system

        saveDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(saveDir, romFile.nameWithoutExtension + ".srm")

        val corePath = File(applicationInfo.nativeLibraryDir, system.coreLib)
        if (!corePath.exists()) {
            return fail(trf("Este console precisa de um core que não veio nesta versão do app (%s)", system.coreLib))
        }

        val view = try {
            val data = GLRetroViewData(this).apply {
                coreFilePath = corePath.absolutePath
                gameFilePath = romFile.absolutePath
                systemDirectory = systemDir.absolutePath
                savesDirectory = saveDir.absolutePath
                saveRAMState = if (sramFile.exists()) sramFile.readBytes() else null
            }
            applyShader(data)
            GLRetroView(this, data)
        } catch (e: Exception) {
            return fail(trf("Erro ao iniciar: %s", e.message ?: ""))
        }
        retroView = view
        lifecycle.addObserver(view)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val pointerSource: Int? = try {
            GLRetroView::class.java.getField("MOTION_SOURCE_POINTER").getInt(null)
        } catch (e: Throwable) {
            null
        }

        val ov = try {
            ControlsOverlay(
                this, system, settings,
                onKey = { isDown, keyCode ->
                    view.sendKeyEvent(if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, swapKey(keyCode), 0)
                },
                onLeftStick = { x, y -> view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, 0) },
                onRightStick = { x, y -> view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, 0) },
                onPointer = { x, y, isDown ->
                    if (pointerSource != null) {
                        if (isDown) view.sendMotionEvent(pointerSource, x, y, 0)
                        else view.sendMotionEvent(pointerSource, -1f, -1f, 0)
                    }
                }
            )
        } catch (e: Exception) {
            return fail(trf("Erro nos controles: %s", e.message ?: ""))
        }
        touchControls = ov
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // FPS
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

        // diagnóstico dos botões
        dbgText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            text = tr("toque num botão…")
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
        menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.7f
            background = GradientDrawable().apply { setColor(Color.parseColor("#77000000")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { openMenu() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(48), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        // barra do modo "mover botões"
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6181A20")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun editChip(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        }
        editBar.addView(editChip(tr("✔ Concluir")) { stopEdit() })
        editBar.addView(editChip(tr("↺ Redefinir")) { touchControls?.resetLayout() })
        root.addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        setContentView(root)

        if (shaderFailed) Toast.makeText(this, tr("Esse filtro não é suportado nesta versão da biblioteca."), Toast.LENGTH_LONG).show()

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

    /** Interpolação (suave) ou pixels nítidos, e efeito de TV de tubo. Usa reflexão: se a biblioteca não tiver, ignora. */
    private fun applyShader(data: GLRetroViewData) {
        try {
            val name = when {
                settings.crt -> "CRT"
                settings.interpolation -> "Default"
                else -> "Sharp"
            }
            val inner = Class.forName("com.swordfish.libretrodroid.ShaderConfig\$" + name)
            val instance = inner.getField("INSTANCE").get(null)
            val setter = data.javaClass.methods.first { it.name == "setShader" }
            setter.invoke(data, instance)
        } catch (_: Throwable) {
            shaderFailed = settings.crt || !settings.interpolation
        }
    }

    /** Se "Trocar A↔B e X↔Y" estiver ligado, troca as teclas (para controles com a posição invertida). */
    private fun swapKey(code: Int): Int {
        if (!settings.swapAB) return code
        return when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> code
        }
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            lastFps = frames
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
            requestWindowFeature(Window.FEATURE_NO_TITLE)
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

    private fun scrolled(col: View) = ScrollView(this).apply {
        addView(col)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(330), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        )
    }

    private fun showMain() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(tr("Menu")))
        col.addView(menuButton(tr("Continuar"), accent = true) { menu?.dismiss() })
        col.addView(menuButton(tr("Save State")) { showSlots(save = true) })
        col.addView(menuButton(tr("Load State")) { showSlots(save = false) })
        col.addView(menuButton(if (recorder == null) tr("Gravar vídeo") else tr("■ Parar gravação")) {
            if (recorder == null) { beginRecording(); menu?.dismiss() } else { stopRecording(); showMain() }
        })
        col.addView(menuButton(tr("Configurações Avançadas")) { showAdvanced() })
        col.addView(menuButton(tr("Sair do jogo")) { menu?.dismiss(); finish() })
        setPage("main", scrolled(col))
    }

    // ----------------------------------------------------------------- slots de save state
    private fun stateFile(n: Int) = File(saveDir, romFile.nameWithoutExtension + ".slot$n.state")
    private fun thumbFile(n: Int) = File(saveDir, romFile.nameWithoutExtension + ".slot$n.jpg")

    private fun showSlots(save: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(if (save) tr("Salvar em qual slot?") else tr("Carregar qual slot?")))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 1..SLOTS) row.addView(slotCard(n, save))
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        if (!save) {
            col.addView(menuButton(tr("Abrir Save baixado (.save)")) { importLauncher.launch(arrayOf("*/*")) }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
        }
        col.addView(menuButton(tr("Voltar")) { showMain() })
        setPage("slots", scrolled(col))
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
                if (save) saveToSlot(n)
                else if (has) loadFromSlot(n)
                else Toast.makeText(this@GameActivity, tr("Slot vazio"), Toast.LENGTH_SHORT).show()
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
            text = trf("Slot %d", n)
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (has) DateFormat.format("dd/MM/yy HH:mm", Date(stateFile(n).lastModified())).toString() else tr("Vazio")
            setTextColor(Color.parseColor("#9AA0AA")); textSize = 12f
        })
        if (has && !save) {
            card.addView(TextView(this).apply {
                text = tr("Exportar")
                setTextColor(Color.WHITE); textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3A3F4B")); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) }
                setOnClickListener { startExport(n) }
            })
        }
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
                Toast.makeText(this@GameActivity, if (ok) trf("Salvo no slot %d", n) else tr("Falha ao salvar"), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@GameActivity, if (ok) trf("Slot %d carregado", n) else tr("Falha ao carregar"), Toast.LENGTH_SHORT).show()
            if (ok) menu?.dismiss()
        }
    }

    // ----------------------------------------------------------------- exportar / importar .save
    private var exportSlot = 0

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) writeExport(uri, exportSlot)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readImport(uri)
    }

    private fun startExport(n: Int) {
        exportSlot = n
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "${romFile.nameWithoutExtension}-slot$n.save")
        exportLauncher.launch(i)
    }

    private fun writeExport(uri: Uri, n: Int) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val state = stateFile(n).readBytes()
                    val thumb = thumbFile(n).takeIf { it.exists() }?.readBytes()
                    val data = SaveFile.pack(state, thumb, gameSystem, romFile.nameWithoutExtension)
                    contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
                } catch (e: Exception) { false }
            }
            Toast.makeText(this@GameActivity, if (ok) tr("Save exportado") else tr("Falha ao exportar"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun readImport(uri: Uri) {
        lifecycleScope.launch {
            val unpacked = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) null else SaveFile.unpack(bytes)
                } catch (e: Exception) { null }
            }
            if (unpacked == null) {
                Toast.makeText(this@GameActivity, tr("Arquivo .save inválido"), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (unpacked.system != gameSystem.name) {
                Toast.makeText(this@GameActivity, trf("Esse save é de outro console (%s)", unpacked.system), Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = Array(SLOTS) { trf("Slot %d", it + 1) }
            AlertDialog.Builder(this@GameActivity)
                .setTitle(tr("Salvar em qual slot?"))
                .setItems(labels) { _, which ->
                    val n = which + 1
                    try {
                        stateFile(n).writeBytes(unpacked.state)
                        val t = unpacked.thumb
                        if (t != null) thumbFile(n).writeBytes(t) else thumbFile(n).delete()
                        Toast.makeText(this@GameActivity, trf("Save importado no slot %d", n), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@GameActivity, tr("Falha ao salvar"), Toast.LENGTH_SHORT).show()
                    }
                    if (menu?.isShowing == true) showSlots(save = false)
                }
                .setNegativeButton(tr("Cancelar"), null)
                .show()
        }
    }

    // ----------------------------------------------------------------- vídeo (com áudio)
    private val audioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askProjection() else startRecording(null, tr("Sem permissão de áudio: gravando só a imagem"))
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            RecordService.start(applicationContext, res.resultCode, data) { projection ->
                runOnUiThread {
                    startRecording(projection, if (projection == null) tr("Não consegui capturar o áudio: gravando só a imagem") else null)
                }
            }
        } else {
            startRecording(null, tr("Captura de áudio negada: gravando só a imagem"))
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
            startRecording(null, tr("Não consegui pedir a captura de áudio: gravando só a imagem"))
        }
    }

    private fun startRecording(projection: MediaProjection?, note: String?) {
        val v = retroView as? SurfaceView ?: return
        val ov = touchControls ?: return
        val r = VideoRecorder(
            v, applicationContext, gameSystem, { ov.pressedIds }, projection,
            settings.recInputs, settings.recFps, settings.recControls, { lastFps }, ov
        )
        if (r.start()) {
            recorder = r
            recText.visibility = View.VISIBLE
            val msg = note ?: if (r.hasAudio) tr("Gravando com áudio… (menu → parar)") else tr("Gravando só a imagem… (menu → parar)")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (projection != null && !r.hasAudio) {
                Toast.makeText(this, trf("Áudio falhou: %s", r.audioError ?: "?"), Toast.LENGTH_LONG).show()
            }
        } else {
            RecordService.stop(applicationContext)
            Toast.makeText(this, tr("Não consegui iniciar a gravação"), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recText.visibility = View.GONE
        Toast.makeText(this, tr("Salvando vídeo…"), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val ok = r.stopAndSave()
            RecordService.stop(app)
            ui.post {
                val msg = if (ok) trf("Vídeo salvo em Filmes/RetroDroid (áudio: %s)", if (r.audioSamples > 0) tr("sim") else tr("não"))
                else tr("Falha ao salvar o vídeo")
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ----------------------------------------------------------------- mover botões
    private fun startEdit() {
        menu?.dismiss()
        pauseGame()
        touchControls?.editMode = true
        editBar.visibility = View.VISIBLE
        menuBtn.visibility = View.GONE
        Toast.makeText(this, tr("Arraste os botões. Toque em Concluir quando terminar."), Toast.LENGTH_LONG).show()
    }

    private fun stopEdit() {
        touchControls?.editMode = false
        editBar.visibility = View.GONE
        menuBtn.visibility = View.VISIBLE
        resumeGame()
    }

    // ----------------------------------------------------------------- configurações avançadas
    private fun showAdvanced() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(tr("Configurações Avançadas")))

        col.addView(SettingsUi.switchRow(this, tr("Mostrar FPS"), settings.showFps) {
            settings.showFps = it
            fpsText.visibility = if (it) View.VISIBLE else View.GONE
        })
        col.addView(SettingsUi.switchRow(this, tr("Vibração ao tocar nos botões"), settings.haptics) { settings.haptics = it })
        col.addView(SettingsUi.switchRow(this, tr("Trocar A↔B e X↔Y (use se um botão fizer a ação errada)"), settings.swapAB) { settings.swapAB = it })
        col.addView(SettingsUi.switchRow(this, tr("Diagnóstico dos botões (mostra a tecla enviada)"), settings.debugInput) {
            settings.debugInput = it
            dbgText.visibility = if (it) View.VISIBLE else View.GONE
        })

        val opacityLabel = TextView(this).apply {
            text = trf("Opacidade dos controles: %d%%", settings.overlayOpacity)
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
                    opacityLabel.text = trf("Opacidade dos controles: %d%%", p + 20)
                    touchControls?.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(TextView(this).apply {
            text = tr("Velocidade do jogo")
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

        col.addView(menuButton(tr("Configurações de gravação")) { showSub(tr("Configurações de gravação"), SettingsUi.recordingView(this, settings)) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })
        col.addView(menuButton(tr("Exibição (suavização e TV de tubo)")) { showSub(tr("Exibição (suavização e TV de tubo)"), SettingsUi.displayView(this, settings)) })
        col.addView(menuButton(tr("Mover os botões de lugar")) { startEdit() })
        col.addView(menuButton(tr("Redefinir posição dos botões")) {
            touchControls?.resetLayout()
            Toast.makeText(this, tr("Posições redefinidas"), Toast.LENGTH_SHORT).show()
        })
        col.addView(menuButton(tr("Voltar")) { showMain() })
        setPage("advanced", scrolled(col))
    }

    private fun showSub(titleText: String, content: View) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(titleText))
        col.addView(content)
        col.addView(menuButton(tr("Voltar")) { showAdvanced() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
        setPage("sub", scrolled(col))
    }

    // ===================================================================== controle físico
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (fromPad && event.keyCode != KeyEvent.KEYCODE_BACK && menu?.isShowing != true) {
            retroView?.sendKeyEvent(event.action, swapKey(event.keyCode), 0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
