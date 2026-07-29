package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCalibrationPayloadTests {
    @Test
    fun roundTrip_preservesCanonicalAnchorsAndScalarState() {
        val payload = WearCalibrationPayload(
            sensorId = "SIBI:6CA04230E260",
            revision = 42L,
            valuesPrecalibrated = true,
            hideInitialWhenCalibrated = true,
            auto = WearCalibrationMode(
                doubleArrayOf(100.0, 110.0, 1_700_000_000_000.0),
            ),
            raw = WearCalibrationMode(
                doubleArrayOf(
                    95.0, 110.0, 1_700_000_000_000.0,
                    130.0, 125.0, 1_700_000_300_000.0,
                ),
            ),
        )

        val decoded = WearCalibrationPayload.decode(WearCalibrationPayload.encode(payload))

        requireNotNull(decoded)
        assertEquals(payload.sensorId, decoded.sensorId)
        assertEquals(payload.revision, decoded.revision)
        assertTrue(decoded.valuesPrecalibrated)
        assertTrue(decoded.hideInitialWhenCalibrated)
        assertArrayEquals(payload.auto.anchorsMgdl, decoded.auto.anchorsMgdl, 0.0)
        assertArrayEquals(payload.raw.anchorsMgdl, decoded.raw.anchorsMgdl, 0.0)
    }

    @Test
    fun decode_rejectsMalformedOrTrailingData() {
        assertNull(WearCalibrationPayload.decode(null))
        assertNull(WearCalibrationPayload.decode(byteArrayOf(1, 0, 4, 1, 2)))

        val valid = WearCalibrationPayload.encode(
            WearCalibrationPayload(
                sensorId = "sensor",
                revision = 1L,
                valuesPrecalibrated = false,
                hideInitialWhenCalibrated = false,
                auto = WearCalibrationMode(DoubleArray(0)),
                raw = WearCalibrationMode(DoubleArray(0)),
            ),
        )
        assertFalse(WearCalibrationPayload.decode(valid)!!.valuesPrecalibrated)
        assertNull(WearCalibrationPayload.decode(valid + byteArrayOf(1)))
    }

    @Test
    fun displayGate_rejectsOnlyValuesTooLargeToBeGlucose() {
        // A driver diagnostic (Ottai's electrode current) must never render as
        // a reading, but a genuinely low reading still has to be shown — some
        // drivers' raw lanes sit far below the physiological floor.
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(20f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(600f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(10.8f, isMmol = false))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(0f, isMmol = false))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(11_557f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(0.6f, isMmol = true))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(34f, isMmol = true))
    }

    @Test
    fun storageGate_keepsBothPhysiologicalBounds() {
        // What gets stored or synced is held to the tighter range.
        assertTrue(GlucoseValuePlausibility.isPlausibleMgdl(20f))
        assertTrue(GlucoseValuePlausibility.isPlausibleMgdl(600f))
        assertFalse(GlucoseValuePlausibility.isPlausibleMgdl(19.9f))
        assertFalse(GlucoseValuePlausibility.isPlausibleMgdl(11_557f))
    }
}
