package tk.glucodata.data.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTreatmentUploaderTests {
    private fun preset(countsTowardIob: Boolean) = JournalInsulinPresetEntity(
        id = 1,
        displayName = "Test insulin",
        onsetMinutes = 30,
        durationMinutes = 720,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    @Test
    fun longInsulinCanBeExcludedWithoutSuppressingOtherTreatments() {
        assertFalse(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = true),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.CARBS.storageValue,
                null,
                sendLongInsulin = false
            )
        )
    }

    @Test
    fun longInsulinRemainsEnabledByTheUploadPolicyWhenRequested() {
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = true
            )
        )
    }
}
