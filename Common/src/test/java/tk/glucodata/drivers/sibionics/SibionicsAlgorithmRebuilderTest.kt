package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAlgorithmRebuilderTest {
    @Test
    fun calibratedReplayUsesPreparedBatchValuesAtOneMinuteCadence() {
        val samples = (1..8).map(::sample)
        val replay = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "test",
            sourceSamples = samples,
            selection = SibionicsAlgorithmSelection.STOCK_CALIBRATED,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = true,
        ) { values, timestamps ->
            assertEquals(values.size, timestamps.size)
            FloatArray(values.size) { 5.7f }
        }

        assertEquals(samples.size, replay.readings.size)
        assertTrue(replay.readings.all { kotlin.math.abs(it.glucoseMgdl - 102.6f) < 0.01f })
        assertTrue(replay.readings.zipWithNext().all { (a, b) -> b.sampleMs - a.sampleMs == 60_000L })
    }

    @Test
    fun stockReplayDoesNotInvokeCalibrationEvaluator() {
        val replay = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "stock",
            sourceSamples = (0..4).map(::sample),
            selection = SibionicsAlgorithmSelection.STOCK,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = true,
        ) { _, _ -> error("stock mode must not request integrated calibration") }

        assertEquals(5, replay.readings.size)
    }

    @Test
    fun requiresCompleteSequenceFromIndexZeroOrOne() {
        assertTrue(SibionicsAlgorithmRebuilder.isContiguousFromSensorStart((1..4).map(::sample)))
        assertFalse(SibionicsAlgorithmRebuilder.isContiguousFromSensorStart(listOf(sample(2), sample(3))))
        assertFalse(SibionicsAlgorithmRebuilder.isContiguousFromSensorStart(listOf(sample(1), sample(3))))
    }

    @Test
    fun persistsStockModelValuesNearestToCalibrationAnchors() {
        val start = 1_700_000_000_000L
        val baseline = SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
            displayStock = floatArrayOf(8f, 8.2f, 8.4f, 8.6f),
            timestamps = longArrayOf(start, start + 60_000L, start + 120_000L, start + 180_000L),
            packedAnchors = doubleArrayOf(13.0, 10.0, (start + 70_000L).toDouble()),
        )

        requireNotNull(baseline)
        assertEquals(1, baseline.values.size)
        assertEquals(8.2f, baseline.values[0], 0.001f)
        assertEquals(start + 60_000L, baseline.timestamps[0])
    }

    @Test
    fun doesNotSeedBaselineFromUnrelatedHistory() {
        val start = 1_700_000_000_000L
        val baseline = SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
            displayStock = floatArrayOf(8f, 8.2f),
            timestamps = longArrayOf(start, start + 60_000L),
            packedAnchors = doubleArrayOf(13.0, 10.0, (start + 12L * 60_000L).toDouble()),
        )

        assertEquals(null, baseline)
    }

    private fun sample(index: Int) = SibionicsSourceSample(
        index = index,
        timestampMs = 1_700_000_000_000L + index * 60_000L,
        rawMmol = 8f,
        temperatureC = 34f,
        impedance = 1_000f,
        variantId = SibionicsConstants.Variant.CHINESE.ordinal,
    )
}
