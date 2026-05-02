package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "journal_pending_deletes")
data class JournalPendingDeleteEntity(
    @PrimaryKey
    val entryId: Long,
    val nsRemoteId: String,
    val deletedAt: Long
)
