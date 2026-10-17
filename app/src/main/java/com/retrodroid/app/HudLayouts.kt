// ARQUIVO GERADO (hud.py). Unidades: controle de 120 x 64.
package com.retrodroid.app

class HudBody(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float)
class HudEl(val id: String, val shape: Char, val x: Float, val y: Float, val w: Float, val h: Float, val label: String)

object HudLayouts {
    fun bodies(s: GameSystem): List<HudBody> = when (s) {
        GameSystem.SNES -> listOf(HudBody(4f, 12f, 112f, 46f, 22f))
        GameSystem.MEGADRIVE -> listOf(HudBody(4f, 14f, 112f, 44f, 20f))
        GameSystem.NES -> listOf(HudBody(4f, 14f, 112f, 44f, 5f))
        GameSystem.N64 -> listOf(HudBody(4f, 10f, 112f, 24f, 12f), HudBody(3f, 14f, 32f, 46f, 15f), HudBody(44f, 14f, 32f, 48f, 12f), HudBody(85f, 14f, 32f, 46f, 15f))
    }

    fun elements(s: GameSystem): List<HudEl> = when (s) {
        GameSystem.SNES -> listOf(
            HudEl("l", 'r', 8f, 4f, 28f, 9f, "L"),
            HudEl("r", 'r', 84f, 4f, 28f, 9f, "R"),
            HudEl("up", 'r', 24f, 22f, 8f, 11f, ""),
            HudEl("down", 'r', 24f, 33f, 8f, 11f, ""),
            HudEl("left", 'r', 17f, 29f, 11f, 8f, ""),
            HudEl("right", 'r', 28f, 29f, 11f, 8f, ""),
            HudEl("a", 'c', 98.0f, 28.0f, 10f, 10f, "A"),
            HudEl("b", 'c', 89.0f, 37.0f, 10f, 10f, "B"),
            HudEl("x", 'c', 89.0f, 19.0f, 10f, 10f, "X"),
            HudEl("y", 'c', 80.0f, 28.0f, 10f, 10f, "Y"),
            HudEl("select", 'r', 45f, 44f, 12f, 4f, ""),
            HudEl("start", 'r', 60f, 44f, 12f, 4f, "")
        )
        GameSystem.MEGADRIVE -> listOf(
            HudEl("up", 'r', 24f, 25f, 8f, 11f, ""),
            HudEl("down", 'r', 24f, 36f, 8f, 11f, ""),
            HudEl("left", 'r', 17f, 32f, 11f, 8f, ""),
            HudEl("right", 'r', 28f, 32f, 11f, 8f, ""),
            HudEl("a", 'c', 69.5f, 39.5f, 9f, 9f, "A"),
            HudEl("b", 'c', 82.5f, 39.5f, 9f, 9f, "B"),
            HudEl("c", 'c', 95.5f, 39.5f, 9f, 9f, "C"),
            HudEl("x", 'c', 70.0f, 27.0f, 8f, 8f, "X"),
            HudEl("y", 'c', 83.0f, 27.0f, 8f, 8f, "Y"),
            HudEl("z", 'c', 96.0f, 27.0f, 8f, 8f, "Z"),
            HudEl("mode", 'r', 48f, 28f, 14f, 4f, ""),
            HudEl("start", 'r', 48f, 42f, 14f, 4f, "")
        )
        GameSystem.NES -> listOf(
            HudEl("up", 'r', 22f, 25f, 8f, 11f, ""),
            HudEl("down", 'r', 22f, 36f, 8f, 11f, ""),
            HudEl("left", 'r', 15f, 32f, 11f, 8f, ""),
            HudEl("right", 'r', 26f, 32f, 11f, 8f, ""),
            HudEl("select", 'r', 46f, 45f, 12f, 4f, ""),
            HudEl("start", 'r', 62f, 45f, 12f, 4f, ""),
            HudEl("b", 'c', 84.5f, 36.5f, 11f, 11f, "B"),
            HudEl("a", 'c', 99.5f, 36.5f, 11f, 11f, "A")
        )
        GameSystem.N64 -> listOf(
            HudEl("l", 'r', 6f, 3f, 24f, 8f, "L"),
            HudEl("r", 'r', 90f, 3f, 24f, 8f, "R"),
            HudEl("z", 'r', 49f, 3f, 22f, 8f, "Z"),
            HudEl("stick", 'c', 52.5f, 32.5f, 15f, 15f, ""),
            HudEl("start", 'r', 54f, 20f, 12f, 5f, ""),
            HudEl("b", 'c', 88.5f, 36.5f, 9f, 9f, "B"),
            HudEl("a", 'c', 101.0f, 41.0f, 10f, 10f, "A"),
            HudEl("c_up", 'c', 98.0f, 12.0f, 6f, 6f, ""),
            HudEl("c_down", 'c', 98.0f, 25.0f, 6f, 6f, ""),
            HudEl("c_left", 'c', 91.5f, 18.5f, 6f, 6f, ""),
            HudEl("c_right", 'c', 104.5f, 18.5f, 6f, 6f, "")
        )
    }
}
