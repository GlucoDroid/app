package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Test

class AnytimeProfileTests {

    @Test
    fun ct3YuwellUsesThreeMinuteCadenceAndFourteenDayProfile() {
        val profile = AnytimeProfileResolver.resolve("SN26-test")

        assertEquals(AnytimeConstants.Family.CT3_YUWELL, profile.family)
        assertEquals(3, profile.readingIntervalMinutes)
        assertEquals(14, profile.ratedLifetimeDays)
        assertEquals(6740, profile.endNumber)
    }

    @Test
    fun shorterVendorProfileStillUsesThreeMinuteCadence() {
        val profile = AnytimeProfileResolver.resolve("SN28-test")

        assertEquals(AnytimeConstants.Family.CT3_YUWELL, profile.family)
        assertEquals(3, profile.readingIntervalMinutes)
        assertEquals(7, profile.ratedLifetimeDays)
        assertEquals(3380, profile.endNumber)
    }
}
