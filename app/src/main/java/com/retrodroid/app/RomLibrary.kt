package com.retrodroid.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Guarda uma cópia das ROMs dentro do app (o core precisa de um caminho de arquivo real). */
class RomLibrary(private val context: Context) {

    private val romsDir = File(context.filesDir, "roms").apply { mkdirs() }

    fun list(): List<File> =
        (romsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in RomDetector.supportedExtensions }
            .sortedBy { it.name.lowercase() }

    /** Retorna o arquivo importado, ou null se o formato não for suportado. */
    fun import(uri: Uri): File? {
        val name = displayName(uri) ?: "rom_${System.currentTimeMillis()}.bin"
        val dest = File(romsDir, name.replace('/', '_'))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (RomDetector.detect(dest) == null) {
            dest.delete()
            return null
        }
        return dest
    }

    fun delete(rom: File) {
        rom.delete()
        val saves = File(context.filesDir, "saves")
        File(saves, rom.nameWithoutExtension + ".srm").delete()
        File(saves, rom.nameWithoutExtension + ".state").delete()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
}
