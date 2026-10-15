package com.waytoconsistency

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure GPS distance rules. Android service lifecycle and storage stay outside this class. */
object RunDistanceCalculator {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val wallTimeMs: Long,
        val elapsedRealtimeMs: Long
    )

    data class Movement(val accepted: Boolean, val distanceMeters: Double = 0.0, val reason: String = "accepted")
    data class Split(val kilometer: Int, val elapsedMs: Long)

    const val MAX_ACCURACY_METERS = 35.0
    const val MAX_SPEED_METERS_PER_SECOND = 8.0
    const val MIN_POINT_DISTANCE_METERS = 2.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun valid(fix: Fix): Boolean =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0.0..MAX_ACCURACY_METERS &&
            fix.elapsedRealtimeMs >= 0

    /** A rejected fix never becomes the next anchor. This allows small movements to accumulate. */
    fun movement(anchor: Fix?, current: Fix): Movement {
        if (!valid(current)) return Movement(false, reason = "invalid")
        if (anchor == null) return Movement(true, 0.0, "first")
        if (!valid(anchor)) return Movement(true, 0.0, "new_anchor")
        val seconds = (current.elapsedRealtimeMs - anchor.elapsedRealtimeMs) / 1000.0
        if (seconds <= 0.0) return Movement(false, reason = "non_monotonic")
        val distance = haversineMeters(anchor.latitude, anchor.longitude, current.latitude, current.longitude)
        val noiseFloor = max(MIN_POINT_DISTANCE_METERS, min(anchor.accuracyMeters, current.accuracyMeters) * 0.5)
        if (distance < noiseFloor) return Movement(false, reason = "below_noise_floor")
        if (distance / seconds > MAX_SPEED_METERS_PER_SECOND) return Movement(false, reason = "impossible_speed")
        return Movement(true, distance)
    }

    fun crossedSplits(oldDistanceMeters: Double, newDistanceMeters: Double, oldElapsedMs: Long, newElapsedMs: Long): List<Split> {
        if (newDistanceMeters <= oldDistanceMeters) return emptyList()
        val first = floor(oldDistanceMeters / 1000.0).toInt() + 1
        val last = floor(newDistanceMeters / 1000.0).toInt()
        if (last < first) return emptyList()
        val distanceDelta = newDistanceMeters - oldDistanceMeters
        val elapsedDelta = (newElapsedMs - oldElapsedMs).coerceAtLeast(0)
        return (first..last).map { kilometer ->
            val fraction = ((kilometer * 1000.0 - oldDistanceMeters) / distanceDelta).coerceIn(0.0, 1.0)
            Split(kilometer, oldElapsedMs + (elapsedDelta * fraction).toLong())
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latDelta / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lonDelta / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
