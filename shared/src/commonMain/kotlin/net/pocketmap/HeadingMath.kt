package net.pocketmap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI
object HeadingMath {
    fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    fun smooth(previous: Float, target: Float, amount: Float): Float = normalize(previous + (normalize(target - previous + 180f) - 180f) * amount.coerceIn(0f, 1f))
    fun azimuth(east: Float, north: Float): Float? = if (hypot(east.toDouble(), north.toDouble()) < 0.15) null else normalize((atan2(east.toDouble(), north.toDouble()) * 180.0 / PI).toFloat())
}
