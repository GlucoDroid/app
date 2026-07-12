package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAdaptiveAlgorithmTest {
    @Test
    fun exactMeasurementRemainsStableWithoutCalibrationOrNoise() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        val outputs = (1..20).map { index ->
            context.process(6f, 3f, 34f, 100f, index, index * 60_000L, emptyList())
        }
        assertTrue(outputs.all { it == 6f })
    }

    @Test
    fun stockModeUsesIntegratedCalibrationWithoutAdaptiveLag() {
        val algorithm = SibionicsAlgorithmContext("test")
        algorithm.configure(
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            variant = SibionicsConstants.Variant.CHINESE,
            selection = SibionicsAlgorithmSelection.STOCK_CALIBRATED,
        )
        val anchor = SibionicsCalibrationAnchor(8f, 6f, 30_000L)

        val output = algorithm.process(
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            mode = SibionicsAlgorithmMode.LIVE,
            impedance = 100f,
            eventTimeMs = 60_000L,
            calibrationAnchors = listOf(anchor),
        )

        assertEquals(6.4f, output, 0.001f)
    }

    @Test
    fun plainStockModeIgnoresCalibrationAnchors() {
        val algorithm = SibionicsAlgorithmContext("test")
        algorithm.configure(
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            variant = SibionicsConstants.Variant.CHINESE,
            selection = SibionicsAlgorithmSelection.STOCK,
        )
        val output = algorithm.process(
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            mode = SibionicsAlgorithmMode.LIVE,
            eventTimeMs = 60_000L,
            calibrationAnchors = listOf(SibionicsCalibrationAnchor(8f, 6f, 30_000L)),
        )

        assertEquals(8f, output, 0.001f)
    }

    @Test
    fun algorithmFeatureStorageRepresentsBothIndependentToggles() {
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(0))
        assertEquals(SibionicsAlgorithmSelection.STOCK_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(1))
        assertEquals(SibionicsAlgorithmSelection.ADAPTIVE, SibionicsAlgorithmSelection.fromStorage(2))
        assertEquals(SibionicsAlgorithmSelection.ADAPTIVE_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(3))
        assertTrue(!SibionicsAlgorithmSelection.STOCK.calibrationEnabled)
        assertTrue(!SibionicsAlgorithmSelection.STOCK.adaptiveEnabled)
        assertTrue(SibionicsAlgorithmSelection.STOCK_CALIBRATED.calibrationEnabled)
        assertTrue(!SibionicsAlgorithmSelection.STOCK_CALIBRATED.adaptiveEnabled)
        assertTrue(!SibionicsAlgorithmSelection.ADAPTIVE.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.ADAPTIVE.adaptiveEnabled)
        assertTrue(SibionicsAlgorithmSelection.ADAPTIVE_CALIBRATED.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.ADAPTIVE_CALIBRATED.adaptiveEnabled)
    }

    @Test
    fun adaptiveToggleDoesNotImplicitlyEnableCalibration() {
        val anchor = SibionicsCalibrationAnchor(8f, 6f, 30_000L)
        fun firstOutput(selection: SibionicsAlgorithmSelection): Float {
            val algorithm = SibionicsAlgorithmContext("test-${selection.storageId}")
            algorithm.configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
            return algorithm.process(
                rawMmol = 8f,
                temperatureC = 34f,
                index = 1,
                mode = SibionicsAlgorithmMode.LIVE,
                eventTimeMs = 60_000L,
                calibrationAnchors = listOf(anchor),
            )
        }

        assertEquals(8f, firstOutput(SibionicsAlgorithmSelection.ADAPTIVE), 0.001f)
        assertEquals(6.4f, firstOutput(SibionicsAlgorithmSelection.ADAPTIVE_CALIBRATED), 0.001f)
    }

    @Test
    fun preparedCalibrationMeasurementDoesNotReapplyLegacyAnchorMapper() {
        val algorithm = SibionicsAlgorithmContext("prepared")
        algorithm.configure(
            "46HU804EBJ4",
            1.4f,
            SibionicsConstants.Variant.CHINESE,
            SibionicsAlgorithmSelection.STOCK_CALIBRATED,
        )
        val stock = algorithm.processStock(8f, 34f, 1, SibionicsAlgorithmMode.REPLAY)

        val output = algorithm.processPreparedMeasurement(
            stockMmol = stock,
            measurementMmol = 5.7f,
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            impedance = 100f,
            eventTimeMs = 60_000L,
        )

        assertEquals(5.7f, output, 0.001f)
    }

    @Test
    fun calibrationAnchorChangesPersistentSensorStateInsteadOfPostHocOutput() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        val start = context.process(8f, 4f, 34f, 100f, 1, 60_000L, emptyList())
        val anchor = SibionicsCalibrationAnchor(8f, 6f, 90_000L)
        val firstAdjusted = context.process(8f, 4f, 34f, 100f, 2, 120_000L, listOf(anchor))
        val secondAdjusted = context.process(8f, 4f, 34f, 100f, 3, 180_000L, listOf(anchor))

        assertEquals(8f, start, 0.001f)
        assertTrue(firstAdjusted < start)
        assertTrue(secondAdjusted <= firstAdjusted)
        assertTrue(secondAdjusted > 5.5f)
    }

    @Test
    fun telemetryAnomalyDoesNotBecomeAFullOneMinuteGlucoseJump() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(6) { offset ->
            context.process(6f, 3f, 34f, 100f, offset + 1, (offset + 1) * 60_000L, emptyList())
        }
        val anomalous = context.process(12f, 9f, 50f, 10_000f, 7, 420_000L, emptyList())
        assertTrue("anomalous=$anomalous", anomalous in 6f..9.5f)
    }

    @Test
    fun snapshotRestoresAdaptiveGlucoseAndVelocityState() {
        val original = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(8) { offset ->
            val index = offset + 1
            original.process(5f + offset * 0.2f, 2.5f + offset * 0.1f, 34f, 100f, index, index * 60_000L, emptyList())
        }
        val restored = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        assertTrue(restored.restore(original.snapshot()))

        val expected = original.process(6.8f, 3.4f, 34f, 100f, 9, 540_000L, emptyList())
        val actual = restored.process(6.8f, 3.4f, 34f, 100f, 9, 540_000L, emptyList())
        assertEquals(expected, actual, 0.001f)
    }

    @Test
    fun lifetimesMatchEachSibionicsSeries() {
        val day = SibionicsConstants.DAY_MS
        assertEquals(14L * day, SibionicsConstants.Variant.EU.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.HEMATONIX.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.CHINESE.officialLifetimeMs)
        assertEquals(22L * day, SibionicsConstants.Variant.SIBIONICS2.officialLifetimeMs)
        assertEquals(23L * day, SibionicsConstants.Variant.SIBIONICS2.expectedLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.GS3.officialLifetimeMs)
        assertEquals(14L * day + 36L * 60L * 60L * 1000L, SibionicsConstants.Variant.GS3.expectedLifetimeMs)
    }

    @Test
    fun gs1ResetUsesObservedFf32Payload() {
        assertArrayEquals(
            byteArrayOf(0x24, 0xE7.toByte(), 0x6F, 0x34),
            SibionicsProtocol.buildGs1ResetPacket(),
        )
    }

    @Test
    fun chineseHistoryProgressCountsRecordsRatherThanPacketIndex() {
        val first = chineseEntry(index = 700, unreceived = 12_340)
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(0, 1, listOf(first)))

        val batch = (0 until 10).map { offset ->
            chineseEntry(index = 701 + offset, unreceived = 12_339 - offset)
        }
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(12_341, 11, batch))
    }

    private fun chineseEntry(index: Int, unreceived: Int) = SibionicsProtocol.ChineseEntry(
        index = index,
        rawTemperature = 0,
        rawImpedance = 0,
        rawGlucose = 0,
        status = 0,
        numOfUnreceived = unreceived,
        addTimeSeconds = 0,
    )
}
