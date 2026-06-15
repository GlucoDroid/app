package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardGlucoseAlertEvaluatorTests {

    private val activeConfig: (AlertConfig) -> Boolean = { true }

    @Test
    fun lowAndHighConditionsOnlyActivateInTheirOwnDirection() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val low = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 3.9f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val high = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.1f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(AlertType.LOW in low)
        assertFalse(AlertType.HIGH in low)
        assertTrue(AlertType.HIGH in high)
        assertFalse(AlertType.LOW in high)
    }

    @Test
    fun exactThresholdIsNotAnActiveCondition() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val lowBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val highBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(lowBoundary.isEmpty())
        assertTrue(highBoundary.isEmpty())
    }

    @Test
    fun forecastUsesProjectedDisplayValueWithoutReplacingPrimaryValue() {
        val configs = mapOf(
            AlertType.PRE_HIGH to AlertConfig(
                type = AlertType.PRE_HIGH,
                enabled = true,
                threshold = 9.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 8.4f,
            rate = 2.0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        val condition = active.getValue(AlertType.PRE_HIGH)
        assertEquals(8.4f, condition.glucoseValue, 0.001f)
        assertEquals(10.62f, condition.evaluatedValue, 0.02f)
    }

    @Test
    fun forecastWithMissingRateDoesNotFire() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 4.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.2f,
            rate = Float.NaN,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun disabledInactiveAndInvalidThresholdConfigsAreIgnored() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = false, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = Float.NaN),
            AlertType.VERY_HIGH to AlertConfig(AlertType.VERY_HIGH, enabled = true, threshold = 12.0f)
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 13.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH, AlertType.VERY_HIGH),
            isMmol = true,
            isConfigActive = { it.type != AlertType.VERY_HIGH }
        )

        assertTrue(active.isEmpty())
    }
}
