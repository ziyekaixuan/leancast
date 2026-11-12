package io.leancast.domain.cast

import kotlin.math.abs

/**
 * Playback speed steps and cycling rules for the TV remote D-pad.
 *
 * Cycle order starts at 1.0x so the first press reaches 1.25x, then 1.5x,
 * 1.75x, 2.0x, and wraps back to 1.0x:
 * `1.0 -> 1.25 -> 1.5 -> 1.75 -> 2.0 -> 1.0`.
 *
 * Directional stepping instead clamps at the ends: at 2.0x faster stays at
 * 2.0x, at 1.0x slower stays at 1.0x.
 */
object PlaybackSpeedPolicy {

    const val DEFAULT_SPEED = 1.0f

    val STEPS: List<Float> = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** Returns the next speed in the cycle after [current]. */
    fun nextSpeed(current: Float): Float {
        val index = STEPS.indexOfFirst { abs(it - current) < 0.001f }
        if (index < 0) return DEFAULT_SPEED
        return STEPS[(index + 1) % STEPS.size]
    }

    /** One step faster than [current], clamped at the fastest step. */
    fun fasterSpeed(current: Float): Float {
        val index = STEPS.indexOfFirst { abs(it - current) < 0.001f }
        if (index < 0) return DEFAULT_SPEED
        return STEPS[minOf(index + 1, STEPS.lastIndex)]
    }

    /** One step slower than [current], clamped at the slowest step. */
    fun slowerSpeed(current: Float): Float {
        val index = STEPS.indexOfFirst { abs(it - current) < 0.001f }
        if (index < 0) return DEFAULT_SPEED
        return STEPS[maxOf(index - 1, 0)]
    }

    /**
     * Parses an optional UPnP `Speed` argument when it names an explicit
     * non-default supported step (`1.25`, `1.5`, `1.75`, `2` / `2.0`).
     *
     * Standard DLNA control points send `<Speed>1</Speed>` on every `Play`
     * action (including resume after pause), so `"1"` / `"1.0"` returns `null`
     * to avoid wiping out a speed chosen on the TV via remote.
     */
    fun parseExplicitUpnpSpeed(raw: String?): Float? {
        val value = raw?.trim()?.toFloatOrNull() ?: return null
        if (abs(value - DEFAULT_SPEED) < 0.001f) return null
        return STEPS.firstOrNull { abs(it - value) < 0.001f }
    }
}
