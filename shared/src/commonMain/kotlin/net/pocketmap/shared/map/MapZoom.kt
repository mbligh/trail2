package net.pocketmap.shared.map

/** UI/preferences use 256px-world zoom; the renderer uses a 512px world. */
object MapZoom {
    fun renderer(displayed: Double): Double = (displayed - 1.0).coerceIn(2.0, 18.0)
    fun displayed(renderer: Double): Double = renderer + 1.0
}
