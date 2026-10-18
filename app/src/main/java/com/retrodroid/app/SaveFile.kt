package com.retrodroid.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Formato ".save" do RetroDroid: um pacote com o estado do jogo, a foto do momento e o console de origem. */
object SaveFile {

    class Unpacked(val system: String, val rom: String, val state: ByteArray, val thumb: ByteArray?)

    fun pack(state: ByteArray, thumb: ByteArray?, system: GameSystem, rom: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("meta.txt"))
            z.write("format=1\nsystem=${system.name}\nrom=$rom\ncreated=${System.currentTimeMillis()}\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("state.bin"))
            z.write(state)
            z.closeEntry()
            if (thumb != null) {
                z.putNextEntry(ZipEntry("thumb.jpg"))
                z.write(thumb)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun unpack(bytes: ByteArray): Unpacked? = try {
        var system = ""
        var rom = ""
        var state: ByteArray? = null
        var thumb: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val data = z.readBytes()
                when (e.name) {
                    "meta.txt" -> for (line in String(data).lines()) {
                        if (line.startsWith("system=")) system = line.removePrefix("system=").trim()
                        if (line.startsWith("rom=")) rom = line.removePrefix("rom=").trim()
                    }
                    "state.bin" -> state = data
                    "thumb.jpg" -> thumb = data
                }
                e = z.nextEntry
            }
        }
        val st = state
        if (st == null || system.isEmpty()) null else Unpacked(system, rom, st, thumb)
    } catch (e: Exception) {
        null
    }
}
