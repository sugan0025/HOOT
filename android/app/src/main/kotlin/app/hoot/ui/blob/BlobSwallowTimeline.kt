package app.hoot.ui.blob

import kotlin.math.PI
import kotlin.math.sin

data class BlobSwallowFrame(
    val tokenProgress: Float,
    val tokenScale: Float,
    val tokenOpacity: Float,
    val mouthOpen: Float,
    val eyeBlink: Float,
    val tokenVisible: Boolean,
    val gulp: Boolean
)

object BlobSwallowTimeline {
    fun at(progress: Float): BlobSwallowFrame {
        val t = progress.coerceIn(0f, 1f)
        val flight = ease((t / 0.62f).coerceIn(0f, 1f))
        val mouth = when {
            t < 0.62f -> 0.82f + 0.18f * ease(t / 0.62f)
            t < 0.84f -> 1.0f - 0.88f * ease((t - 0.62f) / 0.22f)
            else -> 0.12f * (1.0f - ease((t - 0.84f) / 0.16f))
        }
        val tokenVisible = t < 0.72f
        val twitch = if (t in 0.66f..0.82f) {
            sin(((t - 0.66f) / 0.16f).coerceIn(0f, 1f) * PI).toFloat()
        } else {
            0f
        }
        val tokenScale = 1.0f - 0.68f * flight
        val tokenOpacity = if (t < 0.62f) {
            1.0f - 0.18f * flight
        } else {
            1.0f - ease(((t - 0.62f) / 0.10f).coerceIn(0f, 1f))
        }

        return BlobSwallowFrame(
            tokenProgress = flight,
            tokenScale = tokenScale,
            tokenOpacity = tokenOpacity.coerceIn(0f, 1f),
            mouthOpen = mouth.coerceIn(0f, 1f),
            eyeBlink = twitch.coerceIn(-1f, 1f),
            tokenVisible = tokenVisible,
            gulp = t >= 0.84f
        )
    }

    private fun ease(value: Float): Float = value * value * (3f - 2f * value)
}
