package tk.glucodata.ui.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType

class GlobalAlertSettingsCardTests {
    @Test
    fun applyIsEnabledWhenDraftMatchesAppliedButTargetsDiffer() {
        val draft = soundOnlyDraft()
        val targetWithVibration = draft.copy(type = AlertType.HIGH, vibrationEnabled = true)

        assertTrue(
            shouldEnableApplyToAll(
                draftConfig = draft,
                appliedDraft = draft,
                allConfigs = mapOf(AlertType.HIGH to targetWithVibration)
            )
        )
    }

    @Test
    fun applyIsDisabledWhenDraftMatchesAppliedAndAllTargets() {
        val draft = soundOnlyDraft()

        assertFalse(
            shouldEnableApplyToAll(
                draftConfig = draft,
                appliedDraft = draft,
                allConfigs = mapOf(
                    AlertType.LOW to draft,
                    AlertType.HIGH to draft.copy(type = AlertType.HIGH)
                )
            )
        )
    }

    @Test
    fun applyIsEnabledWhenDraftChangedEvenIfTargetsAlreadyMatch() {
        val applied = soundOnlyDraft()
        val changedDraft = applied.copy(deliveryMode = AlertDeliveryMode.BOTH)

        assertTrue(
            shouldEnableApplyToAll(
                draftConfig = changedDraft,
                appliedDraft = applied,
                allConfigs = mapOf(AlertType.LOW to changedDraft)
            )
        )
    }

    @Test
    fun applyIsEnabledWhenOnlySoundDelayChanged() {
        // Guards that soundDelayEnabled/soundDelaySeconds are part of
        // sameMasterDraft(); otherwise the Apply button stays inert.
        val applied = soundOnlyDraft().copy(vibrationEnabled = true)
        val changedDraft = applied.copy(soundDelayEnabled = true, soundDelaySeconds = 30)

        assertTrue(
            shouldEnableApplyToAll(
                draftConfig = changedDraft,
                appliedDraft = applied,
                allConfigs = mapOf(AlertType.LOW to changedDraft)
            )
        )
    }

    private fun soundOnlyDraft(): AlertConfig {
        return AlertConfig(
            type = AlertType.LOW,
            enabled = true,
            deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY,
            soundEnabled = true,
            vibrationEnabled = false,
            flashEnabled = false
        )
    }
}
