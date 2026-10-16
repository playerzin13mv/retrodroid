package com.retrodroid.app

import java.io.File

/** Cada sistema aponta para o core (.so) que o roda. Os .so são baixados pelo GitHub Actions. */
enum class GameSystem(val label: String, val coreLib: String) {
    SNES("Super Nintendo", "libsnes9x_libretro_android.so"),
    MEGADRIVE("Mega Drive", "libgenesis_plus_gx_libretro_android.so"),
    NES("NES", "libfceumm_libretro_android.so");
}

object RomDetector {
    val supportedExtensions = setOf("sfc", "smc", "gen", "md", "smd", "bin", "nes")

    fun detect(file: File): GameSystem? = when (file.extension.lowercase()) {
        "sfc", "smc" -> GameSystem.SNES
        "nes" -> GameSystem.NES
        "gen", "md", "smd" -> GameSystem.MEGADRIVE
        // .bin é ambíguo: ROMs de Mega Drive têm o texto "SEGA" no cabeçalho (offset 0x100)
        "bin" -> if (hasSegaHeader(file)) GameSystem.MEGADRIVE else null
        else -> null
    }

    private fun hasSegaHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            val buf = ByteArray(0x120)
            val read = input.read(buf)
            read >= 0x110 && String(buf, 0x100, 0x10, Charsets.ISO_8859_1).contains("SEGA")
        }
    } catch (e: Exception) {
        false
    }
}
