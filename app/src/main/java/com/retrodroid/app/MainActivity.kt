package com.retrodroid.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Launcher estilo Switch: fileira horizontal de cards, o do meio cresce; um "tick" a cada jogo que passa. */
class MainActivity : AppCompatActivity() {

    private lateinit var library: RomLibrary
    private lateinit var covers: CoverManager
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var snap: LinearSnapHelper
    private lateinit var adapter: CardAdapter
    private lateinit var gameTitle: TextView
    private lateinit var gameSub: TextView
    private lateinit var countText: TextView
    private lateinit var emptyHint: TextView

    private lateinit var sounds: SoundPool
    private var tickId = 0
    private var centerPos = -1
    private var roms: List<File> = emptyList()
    private var d = 1f
    private var coverTarget: File? = null

    private fun dp(v: Int) = (v * d).toInt()

    private val romPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { library.import(it) } }
            val ok = results.count { it != null }
            val bad = results.size - ok
            val msg = buildString {
                if (ok > 0) append("$ok ROM(s) adicionada(s)")
                if (bad > 0) { if (isNotEmpty()) append(" • "); append("$bad formato(s) não suportado(s)") }
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private val coverPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val rom = coverTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { covers.setCustom(rom, uri) }
            Toast.makeText(this@MainActivity, if (ok) "Capa atualizada" else "Não consegui usar essa imagem", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
        }
    }

    // ===================================================================== UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        library = RomLibrary(this)
        covers = CoverManager(this)

        sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        tickId = sounds.load(this, R.raw.tick, 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#1C212B"), Color.parseColor("#090A0E")))
            setPadding(0, dp(18), 0, dp(12))
        }

        // barra do topo
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        top.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(TextView(this).apply {
            text = "RetroDroid"; textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countText = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#9AA3B2")) }
        top.addView(countText)
        root.addView(top)

        // carrossel
        lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv = RecyclerView(this).apply {
            layoutManager = lm
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = CardAdapter()
        rv.adapter = adapter
        snap = LinearSnapHelper().also { it.attachToRecyclerView(rv) }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { updateCards(true) }
        })
        rv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) rv.post { setupSizes() }
        }

        // título do jogo selecionado
        gameTitle = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(24), 0, dp(24), 0)
        }
        gameSub = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#9AA3B2")); gravity = Gravity.CENTER
        }
        root.addView(gameTitle)
        root.addView(gameSub)

        emptyHint = TextView(this).apply {
            text = "Deslize para escolher  •  toque no jogo do meio para jogar  •  segure para opções"
            textSize = 12f; setTextColor(Color.parseColor("#6E7787")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(emptyHint)

        setContentView(root)
    }

    private val cardH get() = (rv.height * 0.80f).toInt()
    private val cardW get() = (cardH * 0.80f).toInt()

    private fun setupSizes() {
        if (rv.height <= 0) return
        val side = ((rv.width - cardW) / 2).coerceAtLeast(0)
        rv.setPadding(side, 0, side, 0)
        adapter.notifyDataSetChanged()
        rv.post { updateCards(false) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        sounds.release()
    }

    private fun refresh() {
        roms = library.list()
        countText.text = if (roms.isEmpty()) "" else "${roms.size} jogo(s)"
        adapter.notifyDataSetChanged()
        val first = if (roms.isNotEmpty()) 1 else 0
        rv.post {
            if (centerPos < 0 || centerPos > roms.size) lm.scrollToPositionWithOffset(first, (rv.width - cardW) / 2 - rv.paddingLeft)
            updateCards(false)
        }
        fetchMissingCovers()
    }

    /** Baixa em segundo plano as capas que ainda não existem (uma tentativa por ROM). */
    private fun fetchMissingCovers() {
        val todo = roms.filter { !covers.hasCover(it) && !covers.wasTried(it) }
        if (todo.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            for (rom in todo) {
                val sys = RomDetector.detect(rom) ?: continue
                if (covers.fetchOnline(rom, sys)) withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
            }
        }
    }

    // ===================================================================== efeito do carrossel
    private fun updateCards(sound: Boolean) {
        if (rv.childCount == 0) return
        val mid = rv.width / 2f
        val pitch = cardW * 1.05f
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            val dist = abs((v.left + v.right) / 2f - mid)
            val t = min(dist / pitch, 1f)
            val s = 1.10f - 0.24f * t
            v.scaleX = s; v.scaleY = s
            v.alpha = 1f - 0.45f * t
            v.elevation = (1f - t) * dp(12)
            (v.tag as? CardHolder)?.border?.alpha = if (t < 0.25f) 1f - t * 4f else 0f
        }
        val sv = snap.findSnapView(lm) ?: return
        val pos = rv.getChildAdapterPosition(sv)
        if (pos != RecyclerView.NO_POSITION && pos != centerPos) {
            val first = centerPos < 0
            centerPos = pos
            if (!first && sound) {
                sounds.play(tickId, 0.7f, 0.7f, 1, 0, 1f)
                rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            updateTitle()
        }
    }

    private fun updateTitle() {
        if (centerPos <= 0 || centerPos > roms.size) {
            gameTitle.text = if (roms.isEmpty()) "Nenhum jogo ainda" else "Adicionar ROMs"
            gameSub.text = if (roms.isEmpty()) "Toque no card + para escolher seus arquivos" else ".sfc  .smc  .gen  .md  .bin  .nes  .z64  .n64  .v64"
        } else {
            val rom = roms[centerPos - 1]
            gameTitle.text = rom.nameWithoutExtension
            gameSub.text = RomDetector.detect(rom)?.label ?: ""
        }
    }

    // ===================================================================== ações
    private fun play(rom: File) {
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.absolutePath))
    }

    private fun options(rom: File) {
        val sys = RomDetector.detect(rom)
        AlertDialog.Builder(this)
            .setTitle(rom.nameWithoutExtension)
            .setItems(arrayOf("Escolher capa da galeria", "Buscar capa na internet", "Remover ROM e saves")) { _, which ->
                when (which) {
                    0 -> { coverTarget = rom; coverPicker.launch("image/*") }
                    1 -> {
                        if (sys != null) lifecycleScope.launch {
                            covers.clear(rom)
                            val ok = withContext(Dispatchers.IO) { covers.fetchOnline(rom, sys) }
                            Toast.makeText(this@MainActivity, if (ok) "Capa encontrada!" else "Capa não encontrada (o nome do arquivo precisa ser igual ao nome oficial do jogo)", Toast.LENGTH_LONG).show()
                            adapter.notifyDataSetChanged()
                        }
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("Remover \"${rom.nameWithoutExtension}\" e todos os saves dela?")
                        .setPositiveButton("Remover") { _, _ -> library.delete(rom); refresh() }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    // ===================================================================== adapter
    private class CardHolder(
        val card: FrameLayout, val img: ImageView, val badge: TextView,
        val plus: LinearLayout, val border: View
    ) : RecyclerView.ViewHolder(card)

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = roms.size + 1  // posição 0 = card "+"

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val ctx = this@MainActivity
            val card = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()) }
                }
            }
            val match = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }

            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            card.addView(img, match())

            val plus = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(ctx).apply { text = "+"; textSize = 72f; setTextColor(Color.parseColor("#B8C0CF")); gravity = Gravity.CENTER })
                addView(TextView(ctx).apply { text = "Adicionar ROMs"; textSize = 15f; setTextColor(Color.parseColor("#9AA3B2")); gravity = Gravity.CENTER })
            }
            card.addView(plus, match())

            val badge = TextView(ctx).apply {
                textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply { setColor(Color.parseColor("#B0000000")); cornerRadius = dp(8).toFloat() }
            }
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })

            val border = View(ctx).apply {
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(4), Color.WHITE); cornerRadius = dp(20).toFloat() }
                alpha = 0f
            }
            card.addView(border, match())

            val h = CardHolder(card, img, badge, plus, border)
            card.tag = h
            return h
        }

        override fun onBindViewHolder(h: CardHolder, position: Int) {
            h.card.layoutParams = RecyclerView.LayoutParams(cardW, cardH).apply { setMargins(dp(10), 0, dp(10), 0) }

            if (position == 0) {
                h.card.setBackgroundColor(Color.parseColor("#262B37"))
                h.plus.visibility = View.VISIBLE
                h.img.visibility = View.GONE
                h.badge.visibility = View.GONE
                h.card.setOnClickListener {
                    if (position == centerPos) romPicker.launch(arrayOf("*/*")) else rv.smoothScrollToPosition(position)
                }
                h.card.setOnLongClickListener(null)
                h.card.isLongClickable = false
                return
            }

            val rom = roms[position - 1]
            val sys = RomDetector.detect(rom)
            h.card.setBackgroundColor(Color.parseColor("#20242E"))
            h.plus.visibility = View.GONE
            h.img.visibility = View.VISIBLE
            h.badge.visibility = View.VISIBLE
            h.badge.text = sys?.badge ?: "?"
            h.img.setImageBitmap(covers.load(rom) ?: covers.generated(rom))
            h.card.setOnClickListener {
                if (position == centerPos) play(rom) else rv.smoothScrollToPosition(position)
            }
            h.card.setOnLongClickListener { options(rom); true }
        }
    }
}
