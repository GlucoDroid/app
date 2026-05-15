package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeCalibrationPolicyTests {

    @Test
    fun manualCalibrationRequiresKnownTwentyFourHourAge() {
        assertEquals(24, AnytimeCalibrationPolicy.MANUAL_CALIBRATION_MIN_AGE_HOURS)
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(-1))
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(0))
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(23))
        assertTrue(AnytimeCalibrationPolicy.canAcceptManualCalibration(24))
        assertTrue(AnytimeCalibrationPolicy.canAcceptManualCalibration(25))
    }

    @Test
    fun nativeCalibrationStatusAllowsAnythingExceptExplicitNot() {
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_UNKNOWN
            )
        )
        assertFalse(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_NOT
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_CAN
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_HOPE
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_INIT
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_MUST
            )
        )
    }

    @Test
    fun nativeCalibrationStatusNamesMatchOfficialIds() {
        assertEquals("UNKNOWN", AnytimeCalibrationPolicy.calibrationStatusName(-1))
        assertEquals("NOT", AnytimeCalibrationPolicy.calibrationStatusName(0))
        assertEquals("CAN", AnytimeCalibrationPolicy.calibrationStatusName(1))
        assertEquals("HOPE", AnytimeCalibrationPolicy.calibrationStatusName(2))
        assertEquals("INIT", AnytimeCalibrationPolicy.calibrationStatusName(3))
        assertEquals("MUST", AnytimeCalibrationPolicy.calibrationStatusName(4))
    }
}
