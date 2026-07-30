package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding this wrong in the unsafe direction means neither device is reading the
 * sensor, so each way the phone can end up off the air is pinned down here.
 */
class WearPhoneBleReleasePolicyTests {
    private val grace = 4L * 60L * 1000L

    @Test
    fun phoneStandsDownWhileTheWatchKeepsReportingAConnection() {
        assertFalse(
            WearPhoneBleReleasePolicy.phoneShouldHoldSensor(
                WearSensorClaimState.CONNECTED,
                msSinceLastConnectedReport = 30_000L,
                graceMs = grace,
            ),
        )
    }

    @Test
    fun phoneTakesOverWhenTheWatchGoesQuiet() {
        // Out of range, flat battery, app killed: the reports simply stop.
        assertTrue(
            WearPhoneBleReleasePolicy.phoneShouldHoldSensor(
                WearSensorClaimState.CONNECTED,
                msSinceLastConnectedReport = grace + 1L,
                graceMs = grace,
            ),
        )
    }

    @Test
    fun phoneKeepsReadingWhileTheWatchIsOnlyRequesting() {
        assertTrue(
            WearPhoneBleReleasePolicy.phoneShouldHoldSensor(
                WearSensorClaimState.REQUESTING,
                msSinceLastConnectedReport = 0L,
                graceMs = grace,
            ),
        )
    }

    @Test
    fun phoneKeepsReadingWhenTheWatchSaysItDoesNotOwnTheSensor() {
        assertTrue(
            WearPhoneBleReleasePolicy.phoneShouldHoldSensor(
                WearSensorClaimState.PHONE_OWNS,
                msSinceLastConnectedReport = 0L,
                graceMs = grace,
            ),
        )
    }

    @Test
    fun phoneKeepsReadingWhenNothingHasBeenHeardFromTheWatch() {
        assertTrue(
            WearPhoneBleReleasePolicy.phoneShouldHoldSensor(
                null,
                msSinceLastConnectedReport = 0L,
                graceMs = grace,
            ),
        )
    }
}
