package tk.glucodata.logic

import tk.glucodata.GlucosePoint
import kotlin.math.abs

/**
 * Sophisticated engine for processing glucose history to determine trend velocity and acceleration.
 * Uses a weighted sliding window to reduce noise and provide stable indicators.
 */
object TrendEngine {

    enum class TrendState {
        DoubleUp,       // Rapid Rise (> 2.0)
        SingleUp,       // Rise (> 1.0)
        FortyFiveUp,    // Slow Rise (> 0.5)
        Flat,           // Stable (-0.5 to 0.5)
        FortyFiveDown,  // Slow Fall (< -0.5)
        SingleDown,     // Fall (< -1.0)
        DoubleDown,     // Rapid Fall (< -2.0)
        Unknown
    }

    data class TrendResult(
        val state: TrendState,
        val velocity: Float,     // mg/dL per minute
        val acceleration: Float, // Change in velocity
        val confidence: Float    // 0.0 - 1.0 (based on data density)
    )

    /**
     * Calculates the trend based on a list of historical points.
     * @param history List of glucose points, ordered by time descending (newest first).
     */
    fun calculateTrend(history: List<GlucosePoint>, useRaw: Boolean = false): TrendResult {
        if (history.size < 2) return TrendResult(TrendState.Flat, 0f, 0f, 0f)

        // Ensure history is Descending (Newest First) for the default logic
        val newestFirst = if (history.first().timestamp < history.last().timestamp) {
            history.reversed()
        } else {
            history
        }

        // Use the last 15 minutes of data (approx 3-15 points depending on frequency)
        val validPoints = newestFirst.takeWhile { 
             (newestFirst.first().timestamp - it.timestamp) < 15 * 60 * 1000 
        }

        if (validPoints.size < 2) return TrendResult(TrendState.Flat, 0f, 0f, 0f)

        // Calculate Average Velocity (Weighted towards recent)
        // Simple Linear Regression or Weighted Delta could work. 
        // Let's use a weighted delta between adjacent points to prioritize recent change 
        // while smoothing out single-point noise.
        
        var totalWeightedVelocity = 0f
        var totalWeight = 0f
        
        // Use correct value source
        val pFirst = validPoints.first()
        val firstVal = if (useRaw && pFirst.rawValue > 0) pFirst.rawValue else pFirst.value

        // Detect unit scale (Heuristic: < 30 usually means mmol/L)
        val isMmol = firstVal < 30f
        val conversionFactor = if (isMmol) 18.0182f else 1.0f

        // Analyze pairs
        for (i in 0 until validPoints.size - 1) {
            val p1 = validPoints[i]
            val p2 = validPoints[i+1]
            val timeDeltaMin = (p1.timestamp - p2.timestamp) / 60000f
            
            if (timeDeltaMin > 0) {
                // Normalize delta to mg/dL
                val v1 = if (useRaw && p1.rawValue > 0) p1.rawValue else p1.value
                val v2 = if (useRaw && p2.rawValue > 0) p2.rawValue else p2.value
                
                val valueDelta = (v1 - v2) * conversionFactor
                val instantVelocity = valueDelta / timeDeltaMin

                // Outlier Rejection: Ignore non-physiological jumps (e.g. calibration artifacts)
                // Threshold: 20 mg/dL per minute (~1.1 mmol/L per min) is extremely high 
                // (Max normal rise is ~3-5).
                if (Math.abs(instantVelocity) > 20f) {
                    continue
                }
                
                // Weight: More recent = higher weight
                // Decay weight by 0.6 per step (was 0.8) for higher responsiveness
                val weight = Math.pow(0.6, i.toDouble()).toFloat()
                
                totalWeightedVelocity += instantVelocity * weight
                totalWeight += weight
            }
        }

        val velocity = if (totalWeight > 0) totalWeightedVelocity / totalWeight else 0f

        // Acceleration: Compare velocity of first half vs second half of window
        // (Rough approximation)
        val acceleration = 0f // Placeholder for now, can refine if needed for curving

        // Map to State
        val state = when {
            velocity > 2.0 -> TrendState.DoubleUp
            velocity > 1.0 -> TrendState.SingleUp
            velocity > 0.5 -> TrendState.FortyFiveUp
            velocity > -0.5 -> TrendState.Flat
            velocity > -1.0 -> TrendState.FortyFiveDown
            velocity > -2.0 -> TrendState.SingleDown
            else -> TrendState.DoubleDown
        }

        return TrendResult(state, velocity, acceleration, 1.0f)
    }
}
