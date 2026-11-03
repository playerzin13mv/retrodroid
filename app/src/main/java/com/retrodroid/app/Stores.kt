package com.retrodroid.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tempo de jogo e "último jogado", por ROM. */
object PlayStats {
    private fun p(ctx: Context) = ctx.getSharedPreferences("playstats", Context.MODE_PRIVATE)

    fun add(ctx: Context, rom: File, ms: Long) {
        val prefs = p(ctx)
        prefs.edit()
            .putLong("t_" + rom.name, prefs.getLong("t_" + rom.name, 0L) + ms)
            .putLong("l_" + rom.name, System.currentTimeMillis())
            .apply()
    }

    fun total(ctx: Context, rom: File): Long = p(ctx).getLong("t_" + rom.name, 0L)
    fun last(ctx: Context, rom: File): Long = p(ctx).getLong("l_" + rom.name, 0L)
}

/** Lista dos vídeos gravados dentro do RetroDroid (tela "Meus vídeos"). */
object RecordingStore {
    class Rec(val uri: Uri, val name: String, val time: Long)

    private fun p(ctx: Context) = ctx.getSharedPreferences("recordings", Context.MODE_PRIVATE)

    fun add(ctx: Context, uri: Uri, name: String) {
        try {
            val arr = JSONArray(p(ctx).getString("list", "[]"))
            arr.put(JSONObject().put("uri", uri.toString()).put("name", name).put("time", System.currentTimeMillis()))
            p(ctx).edit().putString("list", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun remove(ctx: Context, uri: Uri) {
        try {
            val arr = JSONArray(p(ctx).getString("list", "[]"))
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("uri") != uri.toString()) keep.put(o)
            }
            p(ctx).edit().putString("list", keep.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** Mais novas primeiro; ignora as que o jogador já apagou da galeria. */
    fun list(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        try {
            val arr = JSONArray(p(ctx).getString("list", "[]"))
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                val alive = try {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
                if (alive) out += Rec(uri, o.getString("name"), o.getLong("time"))
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** Salva vídeos e prints na galeria (Filmes/RetroDroid e Imagens/RetroDroid). */
object MediaSaver {
    fun saveVideo(ctx: Context, file: File, prefix: String): Uri? = try {
        val name = prefix + "_" + System.currentTimeMillis() + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RetroDroid")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
        }
        uri
    } catch (e: Exception) {
        null
    }

    fun savePng(ctx: Context, bmp: Bitmap, prefix: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, prefix + "_" + System.currentTimeMillis() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroDroid")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }
    } catch (e: Exception) {
        false
    }
}
