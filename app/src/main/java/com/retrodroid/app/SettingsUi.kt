package com.retrodroid.app

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
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
            addView(switchRow(ctx, tr("Mostrar os botões apertados (mini-controle)"), s.recInputs) { s.recInputs = it })
            addView(switchRow(ctx, tr("Mostrar o FPS no vídeo"), s.recFps) { s.recFps = it })
            addView(switchRow(ctx, tr("Mostrar os controles da tela no vídeo"), s.recControls) { s.recControls = it })
            addView(switchRow(ctx, tr("Áudio compatível com a gravação (um pouco mais de atraso)"), s.recAudioCompat) { s.recAudioCompat = it })
            addView(note(ctx, tr("O botão de menu nunca aparece no vídeo. A opção de áudio vale na próxima vez que você abrir um jogo.")))
        }
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
