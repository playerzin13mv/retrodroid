package com.retrodroid.app

import java.io.File

/** Cada sistema aponta para o core (.so) que o roda. Os .so são baixados pelo GitHub Actions. */
enum class GameSystem(
    val label: String,
    val badge: String,
    val coreLib: String,
    val thumbDir: String,
    val touchScreen: Boolean = false
) {
    SNES("Super Nintendo", "SNES", "libsnes9x_libretro_android.so", "Nintendo - Super Nintendo Entertainment System"),
    MEGADRIVE("Mega Drive", "MD", "libgenesis_plus_gx_libretro_android.so", "Sega - Mega Drive - Genesis"),
    NES("NES", "NES", "libfceumm_libretro_android.so", "Nintendo - Nintendo Entertainment System"),
    N64("Nintendo 64", "N64", "libmupen64plus_next_gles3_libretro_android.so", "Nintendo - Nintendo 64"),
    DS("Nintendo DS", "DS", "libds_core.so", "Nintendo - Nintendo DS", true),
    N3DS("Nintendo 3DS", "3DS", "lib3ds_core.so", "Nintendo - Nintendo 3DS", true),
    SATURN("Sega Saturn", "SAT", "libsaturn_core.so", "Sega - Saturn");
}

object RomDetector {
    val supportedExtensions = setOf(
        "sfc", "smc", "gen", "md", "smd", "bin", "nes", "z64", "n64", "v64",
        "nds", "3ds", "cci", "cxi", "cue", "iso", "chd"
    )

    fun detect(file: File): GameSystem? = when (file.extension.lowercase()) {
        "sfc", "smc" -> GameSystem.SNES
        "nes" -> GameSystem.NES
        "gen", "md", "smd" -> GameSystem.MEGADRIVE
        "z64", "n64", "v64" -> GameSystem.N64
        "nds" -> GameSystem.DS
        "3ds", "cci", "cxi" -> GameSystem.N3DS
        "cue", "iso", "chd" -> GameSystem.SATURN
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
