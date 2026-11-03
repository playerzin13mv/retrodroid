package com.retrodroid.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** "Meus vídeos": os vídeos gravados no RetroDroid. Dá pra assistir, compartilhar (YouTube, WhatsApp…) e excluir. */
class VideosActivity : AppCompatActivity() {

    private var d = 1f
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151C"))
            setPadding(dp(18), dp(24), dp(18), dp(18))
        }
        page.addView(TextView(this).apply {
            text = tr("Meus vídeos"); textSize = 24f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        page.addView(TextView(this).apply {
            text = tr("Para postar no YouTube: toque em Compartilhar e escolha o YouTube.")
            textSize = 13f; setTextColor(Color.parseColor("#9AA3B2")); setPadding(0, dp(4), 0, dp(14))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply { setColor(Color.parseColor("#3A3F4B")); cornerRadius = dp(10).toFloat() }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8) }
    }

    private fun refresh() {
        list.removeAllViews()
        val recs = RecordingStore.list(this)
        if (recs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = tr("Nenhum vídeo ainda. Dentro de um jogo, abra o menu e toque em Gravar vídeo.")
                textSize = 15f; setTextColor(Color.parseColor("#9AA3B2")); setPadding(0, dp(30), 0, 0)
            })
            return
        }
        for (r in recs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D212B")); cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = r.name; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(chip(tr("Assistir")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    Toast.makeText(this, tr("Nenhum app para abrir o vídeo"), Toast.LENGTH_SHORT).show()
                }
            })
            row.addView(chip(tr("Compartilhar")) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, r.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, tr("Compartilhar vídeo")))
            })
            row.addView(chip(tr("Excluir")) {
                AlertDialog.Builder(this)
                    .setMessage(tr("Excluir este vídeo?"))
                    .setPositiveButton(tr("Excluir")) { _, _ ->
                        try { contentResolver.delete(r.uri, null, null) } catch (_: Exception) {}
                        RecordingStore.remove(this, r.uri)
                        refresh()
                    }
                    .setNegativeButton(tr("Cancelar"), null)
                    .show()
            })
            card.addView(row)
            list.addView(card)
        }
    }
}
