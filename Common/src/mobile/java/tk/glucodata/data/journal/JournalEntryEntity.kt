package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_entries",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["entryType"]),
        Index(value = ["insulinPresetId"]),
        Index(value = ["sourceRecordId"], unique = true)
    ]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val sensorSerial: String?,
    val entryType: String,
    val title: String,
    val note: String?,
    val amount: Float?,
    val glucoseValueMgDl: Float?,
    val durationMinutes: Int?,
    val intensity: String?,
    val insulinPresetId: Long?,
    val source: String,
    val sourceRecordId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
