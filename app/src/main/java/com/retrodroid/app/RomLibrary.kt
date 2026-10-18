package com.retrodroid.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipInputStream

/** Guarda uma cópia das ROMs dentro do app (o core precisa de um caminho de arquivo real). */
class RomLibrary(private val context: Context) {

    private val romsDir = File(context.filesDir, "roms").apply { mkdirs() }

    fun list(): List<File> =
        (romsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && RomDetector.detect(it) != null }
            .sortedBy { it.name.lowercase() }

    // arquivos que vale a pena tirar de dentro de um .zip/.7z (a ROM e as trilhas de CD)
    private val keepExts = RomDetector.supportedExtensions + setOf("img", "sub", "ccd", "mds", "mdf")

    private fun destFor(entryName: String): File? {
        val base = File(entryName).name
        val ext = base.substringAfterLast('.', "").lowercase()
        return if (base.isNotEmpty() && ext in keepExts) File(romsDir, base) else null
    }

    /** Abre .zip ou .7z e guarda as ROMs de dentro. Retorna a primeira ROM encontrada, ou null. */
    private fun importArchive(uri: Uri, ext: String): File? {
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.$ext")
        var first: File? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return null

            if (ext == "zip") {
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val dest = destFor(e.name)
                            if (dest != null) {
                                dest.outputStream().use { out -> z.copyTo(out) }
                                if (first == null && RomDetector.detect(dest) != null) first = dest
                            }
                        }
                        e = z.nextEntry
                    }
                }
            } else {
                SevenZFile(tmp).use { sz ->
                    val buf = ByteArray(64 * 1024)
                    var e = sz.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val dest = destFor(e.name)
                            if (dest != null) {
                                dest.outputStream().use { out ->
                                    while (true) {
                                        val n = sz.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                    }
                                }
                                if (first == null && RomDetector.detect(dest) != null) first = dest
                            }
                        }
                        e = sz.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            return first
        } finally {
            tmp.delete()
        }
        return first
    }

    /** Retorna o arquivo importado, ou null se o formato não for suportado. */
    fun import(uri: Uri): File? {
        val name = displayName(uri) ?: "rom_${System.currentTimeMillis()}.bin"
        val archiveExt = name.substringAfterLast('.', "").lowercase()
        if (archiveExt == "zip" || archiveExt == "7z") return importArchive(uri, archiveExt)
        val dest = File(romsDir, name.replace('/', '_'))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (RomDetector.detect(dest) == null) {
            // .bin sem cabeçalho de Mega Drive é a trilha de um CD (fica junto do .cue): guarda e não lista
            if (dest.extension.lowercase() == "bin") return dest
            dest.delete()
            return null
        }
        return dest
    }

    /** Copia BIOS/firmware escolhido pelo jogador para a pasta "system" dos cores (mantém o nome do arquivo). */
    fun importBios(uri: Uri): Boolean {
        val name = displayName(uri) ?: return false
        val dir = File(context.filesDir, "system").apply { mkdirs() }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(dir, name.replace('/', '_')).outputStream().use { input.copyTo(it) }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    fun delete(rom: File) {
        rom.delete()
        // apaga SRAM, save states (slots + miniaturas) e capa
        val prefix = rom.nameWithoutExtension + "."
        File(context.filesDir, "saves").listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        File(context.filesDir, "covers").listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
}
