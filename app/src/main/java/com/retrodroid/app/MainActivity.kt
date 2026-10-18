package com.retrodroid.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** Launcher estilo Switch: cards grandes, fundo animado que muda de cor com o jogo, "tick" a cada jogo que passa. */
class MainActivity : AppCompatActivity() {

    private lateinit var library: RomLibrary
    private lateinit var covers: CoverManager
    private lateinit var titles: TitleStore
    private lateinit var settings: Settings
    private lateinit var aurora: AuroraView
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var snap: LinearSnapHelper
    private lateinit var adapter: CardAdapter
    private lateinit var gameTitle: TextView
    private lateinit var gameSub: TextView
    private lateinit var countText: TextView

    private lateinit var sounds: SoundPool
    private var tickId = 0
    private var centerPos = -1
    private var roms: List<File> = emptyList()
    private var d = 1f
    private var coverTarget: File? = null
    private var firstShow = true
    private val defaultAccent = Color.parseColor("#6C63FF")
    private val accentCache = HashMap<String, Int>()

    private fun dp(v: Int) = (v * d).toInt()

    private fun titleOf(rom: File) = titles.get(rom) ?: rom.nameWithoutExtension

    private fun accentOf(rom: File): Int {
        val key = rom.name + "|" + titleOf(rom) + "|" + covers.coverFile(rom).lastModified()
        return accentCache.getOrPut(key) { covers.accent(rom, titleOf(rom)) }
    }

    private fun systemColor(s: GameSystem?): Int = Color.parseColor(
        when (s) {
            GameSystem.SNES -> "#7B5CFF"
            GameSystem.MEGADRIVE -> "#2F7BFF"
            GameSystem.NES -> "#FF4D4D"
            GameSystem.N64 -> "#1FBF7A"
            GameSystem.DS -> "#8E9AAF"
            GameSystem.N3DS -> "#E0457B"
            GameSystem.SATURN -> "#00B8D9"
            null -> "#555555"
        }
    )

    private val romPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { library.import(it) } }
            val ok = results.count { it != null }
            val bad = results.size - ok
            val msg = buildString {
                if (ok > 0) append(trf("%d ROM(s) adicionada(s)", ok))
                if (bad > 0) { if (isNotEmpty()) append(" • "); append(trf("%d formato(s) não suportado(s)", bad)) }
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
            Toast.makeText(this@MainActivity, if (ok) tr("Capa atualizada") else tr("Não consegui usar essa imagem"), Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            updateTitle()
        }
    }

    private val biosPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { uris.count { library.importBios(it) } }
            Toast.makeText(this@MainActivity, trf("%d arquivo(s) de BIOS importado(s)", ok), Toast.LENGTH_LONG).show()
        }
    }

    // ===================================================================== UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        library = RomLibrary(this)
        covers = CoverManager(this)
        titles = TitleStore(this)
        settings = Settings(this)

        sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        tickId = sounds.load(this, R.raw.tick, 1)

        aurora = AuroraView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }

        // barra do topo
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        top.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(TextView(this).apply {
            text = "RetroDroid"; textSize = 24f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f; setPadding(dp(12), 0, 0, 0)
            setShadowLayer(12f, 0f, 2f, Color.parseColor("#66000000"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "⚙"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); shape = GradientDrawable.OVAL }
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        countText = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(20).toFloat() }
            visibility = View.GONE
        }
        top.addView(countText)
        content.addView(top)

        // carrossel
        lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv = RecyclerView(this).apply {
            layoutManager = lm
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = CardAdapter()
        rv.adapter = adapter
        snap = LinearSnapHelper().also { it.attachToRecyclerView(rv) }
        content.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { updateCards(true) }
        })
        rv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) rv.post { setupSizes() }
        }

        // título do jogo selecionado
        gameTitle = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(24), 0, dp(24), 0)
            setShadowLayer(14f, 0f, 3f, Color.parseColor("#88000000"))
        }
        gameSub = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#D0D6E2")); gravity = Gravity.CENTER
        }
        content.addView(gameTitle)
        content.addView(gameSub)
        content.addView(TextView(this).apply {
            text = tr("Deslize para escolher  •  toque no jogo do meio para jogar  •  segure para opções")
            textSize = 12f; setTextColor(Color.parseColor("#8E97A8")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        val root = FrameLayout(this)
        root.addView(aurora, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        rv.post { showCrashIfAny() }
    }

    // ===================================================================== configurações
    private fun showSettings() {
        val items = arrayOf(
            tr("Idioma"),
            tr("Configurações de gravação"),
            tr("Exibição (suavização e TV de tubo)"),
            tr("Importar BIOS / firmware"),
            tr("Sobre")
        )
        AlertDialog.Builder(this)
            .setTitle(tr("Configurações"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseLanguage()
                    1 -> showView(tr("Configurações de gravação"), SettingsUi.recordingView(this, settings))
                    2 -> showView(tr("Exibição (suavização e TV de tubo)"), SettingsUi.displayView(this, settings))
                    3 -> {
                        Toast.makeText(this, tr("Escolha os arquivos de BIOS (o Sega Saturn precisa de um)."), Toast.LENGTH_LONG).show()
                        biosPicker.launch(arrayOf("*/*"))
                    }
                    4 -> AlertDialog.Builder(this)
                        .setTitle("RetroDroid")
                        .setMessage(tr("Versão beta. Feito com LibretroDroid e os cores Snes9x, Genesis Plus GX, FCEUmm, Mupen64Plus-Next, melonDS, Citra e Yabause/YabaSanshiro."))
                        .setPositiveButton(tr("Fechar"), null)
                        .show()
                }
            }
            .show()
    }

    private fun showView(titleText: String, content: View) {
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(content)
            .setPositiveButton(tr("Fechar"), null)
            .show()
    }

    private fun chooseLanguage() {
        val names = I18n.options.map { it.second }.toTypedArray()
        val current = I18n.options.indexOfFirst { it.first == I18n.lang }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(tr("Idioma"))
            .setSingleChoiceItems(names, current) { dlg, which ->
                I18n.save(this, I18n.options[which].first)
                dlg.dismiss()
                recreate()
            }
            .show()
    }

    /** Se o app fechou de repente da última vez, mostra o erro (dá pra copiar e mandar pro Claude). */
    private fun showCrashIfAny() {
        val text = CrashReport.pending(this) ?: return
        val tv = TextView(this).apply {
            this.text = text.take(6000)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("O app fechou por causa de um erro"))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(tr("Copiar")) { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("erro", text))
                CrashReport.clear(this)
                Toast.makeText(this, tr("Copiado! Cole no chat."), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(tr("Fechar")) { _, _ -> CrashReport.clear(this) }
            .show()
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
        countText.visibility = if (roms.isEmpty()) View.GONE else View.VISIBLE
        countText.text = trf("%d jogo(s)", roms.size)
        adapter.notifyDataSetChanged()
        val first = if (roms.isNotEmpty()) 1 else 0
        rv.post {
            if (centerPos < 0 || centerPos > roms.size) lm.scrollToPositionWithOffset(first, (rv.width - cardW) / 2 - rv.paddingLeft)
            updateCards(false)
            if (firstShow) {
                firstShow = false
                rv.alpha = 0f
                rv.translationY = dp(36).toFloat()
                rv.animate().alpha(1f).translationY(0f).setDuration(450).start()
            }
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
                if (covers.fetchOnline(rom, sys)) withContext(Dispatchers.Main) { adapter.notifyDataSetChanged(); updateTitle() }
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
            val s = 1.12f - 0.26f * t
            v.scaleX = s; v.scaleY = s
            v.alpha = 1f - 0.50f * t
            v.elevation = (1f - t) * dp(16)
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
        val isGame = centerPos in 1..roms.size
        if (!isGame) {
            gameTitle.text = if (roms.isEmpty()) tr("Nenhum jogo ainda") else tr("Adicionar ROMs")
            gameSub.text = if (roms.isEmpty()) tr("Toque no card + para escolher seus arquivos") else ".sfc .smc .gen .md .bin .nes .z64 .n64 .v64 .nds .3ds .cue .iso .chd"
            aurora.setAccent(defaultAccent)
        } else {
            val rom = roms[centerPos - 1]
            gameTitle.text = titleOf(rom)
            gameSub.text = RomDetector.detect(rom)?.label ?: ""
            aurora.setAccent(accentOf(rom))
        }
        gameTitle.alpha = 0f
        gameTitle.translationY = dp(8).toFloat()
        gameTitle.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    // ===================================================================== ações
    private fun play(rom: File) {
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.absolutePath))
    }

    private fun options(rom: File) {
        val sys = RomDetector.detect(rom)
        AlertDialog.Builder(this)
            .setTitle(titleOf(rom))
            .setItems(arrayOf(
                tr("Editar nome"), tr("Escolher capa da galeria"), tr("Buscar capa na internet"),
                tr("Restaurar nome e capa originais"), tr("Remover ROM e saves")
            )) { _, which ->
                when (which) {
                    0 -> editName(rom)
                    1 -> { coverTarget = rom; coverPicker.launch("image/*") }
                    2 -> if (sys != null) lifecycleScope.launch {
                        covers.clear(rom)
                        val ok = withContext(Dispatchers.IO) { covers.fetchOnline(rom, sys) }
                        Toast.makeText(this@MainActivity, if (ok) tr("Capa encontrada!") else tr("Capa não encontrada (o nome do arquivo precisa ser igual ao nome oficial do jogo)"), Toast.LENGTH_LONG).show()
                        adapter.notifyDataSetChanged(); updateTitle()
                    }
                    3 -> { titles.clear(rom); covers.clear(rom); adapter.notifyDataSetChanged(); updateTitle(); fetchMissingCovers() }
                    4 -> AlertDialog.Builder(this)
                        .setMessage(trf("Remover %s e todos os saves dela?", titleOf(rom)))
                        .setPositiveButton(tr("Remover")) { _, _ -> titles.clear(rom); library.delete(rom); refresh() }
                        .setNegativeButton(tr("Cancelar"), null).show()
                }
            }.show()
    }

    private fun editName(rom: File) {
        val input = EditText(this).apply {
            setText(titleOf(rom))
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Nome do jogo"))
            .setView(box)
            .setPositiveButton(tr("Salvar")) { _, _ ->
                titles.set(rom, input.text.toString())
                adapter.notifyDataSetChanged(); updateTitle()
            }
            .setNegativeButton(tr("Cancelar"), null)
            .show()
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
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat()) }
                }
            }
            val match = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }

            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            card.addView(img, match())

            val plus = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(ctx).apply { text = "+"; textSize = 80f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                addView(TextView(ctx).apply { text = tr("Adicionar ROMs"); textSize = 16f; setTextColor(Color.parseColor("#D0D6E2")); gravity = Gravity.CENTER })
            }
            card.addView(plus, match())

            val badge = TextView(ctx).apply {
                textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

            val border = View(ctx).apply {
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(4), Color.WHITE); cornerRadius = dp(24).toFloat() }
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
                h.card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#3B3F63"), Color.parseColor("#1D2036")))
                h.plus.visibility = View.VISIBLE
                h.img.visibility = View.GONE
                h.badge.visibility = View.GONE
                (h.border.background as GradientDrawable).setStroke(dp(4), Color.WHITE)
                h.card.outlineSpotShadowColor = defaultAccent
                h.card.outlineAmbientShadowColor = defaultAccent
                h.card.setOnClickListener {
                    if (position == centerPos) romPicker.launch(arrayOf("*/*")) else rv.smoothScrollToPosition(position)
                }
                h.card.setOnLongClickListener(null)
                h.card.isLongClickable = false
                return
            }

            val rom = roms[position - 1]
            val sys = RomDetector.detect(rom)
            val accent = accentOf(rom)
            h.card.setBackgroundColor(Color.parseColor("#14161F"))
            h.plus.visibility = View.GONE
            h.img.visibility = View.VISIBLE
            h.badge.visibility = View.VISIBLE
            h.badge.text = sys?.badge ?: "?"
            h.badge.background = GradientDrawable().apply { setColor(systemColor(sys)); cornerRadius = dp(10).toFloat() }
            (h.border.background as GradientDrawable).setStroke(dp(4), accent)
            h.card.outlineSpotShadowColor = accent
            h.card.outlineAmbientShadowColor = accent
            h.img.setImageBitmap(covers.load(rom) ?: covers.generated(rom, titleOf(rom), sys))
            h.card.setOnClickListener {
                if (position == centerPos) play(rom) else rv.smoothScrollToPosition(position)
            }
            h.card.setOnLongClickListener { options(rom); true }
        }
    }
}
