package com.retrodroid.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import java.io.File

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM = "rom_path"
    }

    private var retroView: GLRetroView? = null
    private lateinit var sramFile: File
    private lateinit var stateFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tela cheia imersiva, sempre acesa
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

        val romFile = File(intent.getStringExtra(EXTRA_ROM) ?: return fail("ROM não informada"))
        val system = RomDetector.detect(romFile) ?: return fail("Formato não suportado")

        val saveDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(saveDir, romFile.nameWithoutExtension + ".srm")
        stateFile = File(saveDir, romFile.nameWithoutExtension + ".state")

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

        val overlay = ControlsOverlay(this, system) { isDown, keyCode ->
            view.sendKeyEvent(if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val d = resources.displayMetrics.density
        val menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.6f
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { showMenu() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams((48 * d).toInt(), (40 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showMenu() {
        val items = arrayOf("Continuar", "Salvar estado", "Carregar estado", "Reiniciar", "Sair do jogo")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    1 -> saveState()
                    2 -> loadState()
                    3 -> retroView?.reset()
                    4 -> finish()
                }
            }
            .show()
    }

    private fun saveState() {
        try {
            val bytes = retroView?.serializeState() ?: return
            stateFile.writeBytes(bytes)
            Toast.makeText(this, "Estado salvo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadState() {
        if (!stateFile.exists()) {
            Toast.makeText(this, "Nenhum estado salvo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            retroView?.unserializeState(stateFile.readBytes())
            Toast.makeText(this, "Estado carregado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Salva a SRAM (save "de bateria", tipo o de Zelda) sempre que o jogo sai da tela. */
    private fun saveSram() {
        try {
            val bytes = retroView?.serializeSRAM() ?: return
            if (bytes.isNotEmpty()) sramFile.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        saveSram()
        super.onPause()
    }

    // Controle físico (bluetooth/USB) já funciona: repassa os botões pro core
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (fromPad && event.keyCode != KeyEvent.KEYCODE_BACK) {
            retroView?.sendKeyEvent(event.action, event.keyCode, 0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
