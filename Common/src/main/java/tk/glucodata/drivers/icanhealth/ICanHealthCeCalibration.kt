package tk.glucodata.drivers.icanhealth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

data class ICanHealthCeCalibrationResult(
    val encodedP: Float,
    val isCalibrated: Boolean,
    val calibCount: Int,
    val calibProduct: Float,
    val currentTimesTemp: Float,
    val temperature: Float,
)

object ICanHealthCeCalibration {

    fun planCalib(
        currentGluMgDl: Float,
        fingerGluMgDl: Float,
        historyMgDl: List<Float>,
        sensorCurrent: Float,
        temperatureC: Float,
    ): ICanHealthCeCalibrationResult {
        val safeProduct = (fingerGluMgDl * sensorCurrent * temperatureC).takeIf { it.isFinite() && abs(it) > 1e-6f }
            ?: return ICanHealthCeCalibrationResult(
                encodedP = 1f,
                isCalibrated = false,
                calibCount = 0,
                calibProduct = 0f,
                currentTimesTemp = sensorCurrent * temperatureC,
                temperature = temperatureC,
            )

        val (slope, _, cv) = lineFit(historyMgDl)
        val confidenceInput = (cv * 0.5f + abs(slope) * 0.5f).coerceAtMost(1f)
        val sigmoid = (0.5f / (1f + exp((-confidenceInput / 10f).toDouble()).toFloat())) + 0.5f
        val blended = sigmoid * currentGluMgDl + (1f - sigmoid) * safeProduct
        val factor = (blended / safeProduct).takeIf { it.isFinite() } ?: 1f

        return ICanHealthCeCalibrationResult(
            encodedP = factor,
            isCalibrated = true,
            calibCount = 1,
            calibProduct = safeProduct,
            currentTimesTemp = sensorCurrent * temperatureC,
            temperature = temperatureC,
        )
    }

    fun startCalib(
        currentGluMgDl: Float,
        isFirstCalibration: Boolean,
        existingCalibrationCount: Int,
        sensorCurrent: Float,
        temperatureC: Float,
    ): ICanHealthCeCalibrationResult {
        val safeCurrent = currentGluMgDl.takeIf { it.isFinite() && it > 0f } ?: 1f
        val count = existingCalibrationCount.coerceAtLeast(0)
        val rampFactor = if (isFirstCalibration) {
            safeCurrent
        } else {
            (((safeCurrent - 1f) * count.toFloat()) / 5f) + 1f
        }
        val nextCount = if (isFirstCalibration) {
            1
        } else if (count < 4) {
            count + 1
        } else {
            0
        }

        return ICanHealthCeCalibrationResult(
            encodedP = safeCurrent,
            isCalibrated = !isFirstCalibration && count < 4,
            calibCount = nextCount,
            calibProduct = sensorCurrent * temperatureC * rampFactor,
            currentTimesTemp = temperatureC,
            temperature = rampFactor,
        )
    }

    private fun lineFit(history: List<Float>): Triple<Float, Float, Float> {
        val samples = history.filter { it.isFinite() && it > 0f }
        if (samples.isEmpty()) {
            return Triple(0f, 0f, 0f)
        }
        val n = samples.size.toFloat()
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f
        samples.forEachIndexed { index, value ->
            val x = index.toFloat()
            sumX += x
            sumY += value
            sumXY += x * value
            sumX2 += x * x
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val denominator = (n * sumX2) - (sumX * sumX)
        val slope = if (abs(denominator) > 1e-6f) {
            ((n * sumXY) - (sumX * sumY)) / denominator
        } else {
            0f
        }
        val intercept = meanY - meanX * slope
        val sumSqDev = samples.sumOf { (it - meanY).pow(2.0f).toDouble() }.toFloat()
        val cv = if (meanY > 1e-6f) {
            (sqrt(sumSqDev / n.toDouble()) / meanY.toDouble()).toFloat()
        } else {
            0f
        }
        return Triple(slope, intercept, cv)
    }
}
