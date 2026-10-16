package com.retrodroid.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var library: RomLibrary
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var roms: List<File> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { library.import(it) } }
            val ok = results.count { it != null }
            val bad = results.size - ok
            val msg = buildString {
                if (ok > 0) append("$ok ROM(s) adicionada(s)")
                if (bad > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("$bad formato(s) não suportado(s)")
                }
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = RomLibrary(this)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "RetroDroid"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "SNES • Mega Drive • NES"
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(2), 0, dp(16))
        })

        root.addView(Button(this).apply {
            text = "Adicionar ROMs"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emptyText = TextView(this).apply {
            text = "Nenhuma ROM ainda.\nToque em \"Adicionar ROMs\" e escolha seus arquivos\n(.sfc .smc .gen .md .bin .nes)"
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
        }
        root.addView(emptyText)

        listView = ListView(this).apply {
            divider = null
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        listView.setOnItemClickListener { _, _, pos, _ ->
            val rom = roms[pos]
            startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.absolutePath))
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val rom = roms[pos]
            AlertDialog.Builder(this)
                .setTitle(rom.nameWithoutExtension)
                .setMessage("Remover esta ROM e os saves dela?")
                .setPositiveButton("Remover") { _, _ -> library.delete(rom); refresh() }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        roms = library.list()
        emptyText.visibility = if (roms.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = object : ArrayAdapter<File>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, roms
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val rom = roms[position]
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = rom.nameWithoutExtension
                    setTextColor(Color.WHITE)
                }
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = RomDetector.detect(rom)?.label ?: "?"
                    setTextColor(Color.parseColor("#9E9E9E"))
                }
                return v
            }
        }
    }
}
