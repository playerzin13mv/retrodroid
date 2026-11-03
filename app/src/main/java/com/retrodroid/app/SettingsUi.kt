package com.retrodroid.app

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** Pedaços de tela de configuração usados no Launcher e dentro do jogo. */
object SettingsUi {

    fun switchRow(ctx: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
        val d = ctx.resources.displayMetrics.density
        return SwitchCompat(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = checked
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
    }

    private fun note(ctx: Context, text: String): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#9AA3B2"))
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
    }

    fun recordingView(ctx: Context, s: Settings): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            addView(switchRow(ctx, tr("Gravar o som do jogo"), s.recGameAudio) { s.recGameAudio = it })
            addView(switchRow(ctx, tr("Gravar a minha voz (microfone)"), s.recMic) { s.recMic = it })
            addView(switchRow(ctx, tr("Abaixar o som do jogo quando eu falar"), s.recDuckOn) { s.recDuckOn = it })

            val duckLabel = TextView(ctx).apply {
                text = trf("Quanto abaixar: %d%%", s.recDuckPercent)
                setTextColor(Color.WHITE); textSize = 15f
                setPadding(0, (6 * d).toInt(), 0, 0)
            }
            addView(duckLabel)
            addView(SeekBar(ctx).apply {
                max = 80
                progress = s.recDuckPercent - 10
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        s.recDuckPercent = p + 10
                        duckLabel.text = trf("Quanto abaixar: %d%%", p + 10)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })

            addView(switchRow(ctx, tr("Mostrar os botões apertados (mini-controle)"), s.recInputs) { s.recInputs = it })
            addView(switchRow(ctx, tr("Mostrar o FPS no vídeo"), s.recFps) { s.recFps = it })
            addView(switchRow(ctx, tr("Mostrar os controles da tela no vídeo"), s.recControls) { s.recControls = it })
            addView(switchRow(ctx, tr("Áudio compatível com a gravação (um pouco mais de atraso)"), s.recAudioCompat) { s.recAudioCompat = it })
            addView(note(ctx, tr("O botão de menu nunca aparece no vídeo. A opção de áudio vale na próxima vez que você abrir um jogo.")))

            addView(TextView(ctx).apply {
                text = tr("Ver relatório da última gravação")
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2D35")); cornerRadius = 12 * d
                    setStroke((1 * d).toInt(), Color.parseColor("#66FFFFFF"))
                }
                setOnClickListener { showReport(ctx) }
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * d).toInt() }
            })
        }
    }

    /** Mostra o relatório passo a passo da última gravação, com botão de copiar. */
    fun showReport(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        val text = RecLog.text(ctx).ifEmpty { tr("Ainda não há relatório. Grave um vídeo primeiro.") }
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(tr("Relatório da gravação"))
            .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(tr("Copiar")) { _, _ ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("relatorio", text))
                android.widget.Toast.makeText(ctx, tr("Copiado! Cole no chat."), android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(tr("Fechar"), null)
            .show()
    }

    fun displayView(ctx: Context, s: Settings): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            addView(switchRow(ctx, tr("Interpolação (imagem mais suave)"), s.interpolation) { s.interpolation = it })
            addView(switchRow(ctx, tr("Efeito de TV de tubo (CRT)"), s.crt) { s.crt = it })
            addView(note(ctx, tr("Vale na próxima vez que você abrir um jogo.")))
        }
    }
}
