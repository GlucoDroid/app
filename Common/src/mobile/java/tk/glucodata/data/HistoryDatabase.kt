package tk.glucodata.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tk.glucodata.data.journal.JournalDao
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity

/**
 * Room database for independent glucose history storage.
 * This database is separate from the C++ native sensor data and
 * persists through "wipe sensor data" operations.
 *
 * Version history:
 *   v2 — original single-sensor schema (timestamp PK, value, rawValue, rate)
 *   v3 — multi-sensor: added sensorSerial column, auto-generated PK, composite unique index
 *   v4 — compatibility columns from a reverted Sibionics experiment (unused by current entity)
 *   v5 — dashboard journal entries and insulin presets
 *   v6 — insulin preset curves for richer activity modeling
 *   v7 — per-preset active-insulin participation flag
 *   v8 — per-reading delete tombstones to keep manual Room deletes durable
 */
@Database(
    entities = [
        HistoryReading::class,
        DeletedHistoryReading::class,
        JournalEntryEntity::class,
        JournalInsulinPresetEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    
    abstract fun historyDao(): HistoryDao
    abstract fun journalDao(): JournalDao
    
    companion object {
        @Volatile
        private var INSTANCE: HistoryDatabase? = null

        /**
         * Migration v2 → v3: Add sensorSerial column for multi-sensor support.
         *
         * Strategy: recreate the table with the new schema and copy existing data,
         * assigning all old rows to a default sensor serial "unknown".
         * A full re-sync from native will later re-tag them correctly.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the new table with auto-generated PK and sensorSerial
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_readings_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT NOT NULL DEFAULT 'unknown',
                        value REAL NOT NULL,
                        rawValue REAL NOT NULL,
                        rate REAL
                    )
                """.trimIndent())
                
                // Copy existing data, defaulting sensorSerial to 'unknown'
                db.execSQL("""
                    INSERT INTO history_readings_new (timestamp, sensorSerial, value, rawValue, rate)
                    SELECT timestamp, 'unknown', value, rawValue, rate FROM history_readings
                """.trimIndent())
                
                // Drop old table and rename new one
                db.execSQL("DROP TABLE history_readings")
                db.execSQL("ALTER TABLE history_readings_new RENAME TO history_readings")
                
                // Create indices
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_readings_timestamp_sensorSerial ON history_readings (timestamp, sensorSerial)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_readings_sensorSerial ON history_readings (sensorSerial)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_readings ADD COLUMN customValue REAL")
                db.execSQL("ALTER TABLE history_readings ADD COLUMN customRate REAL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT,
                        entryType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        amount REAL,
                        glucoseValueMgDl REAL,
                        durationMinutes INTEGER,
                        intensity TEXT,
                        insulinPresetId INTEGER,
                        source TEXT NOT NULL,
                        sourceRecordId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_timestamp ON journal_entries (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_entryType ON journal_entries (entryType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_insulinPresetId ON journal_entries (insulinPresetId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_entries_sourceRecordId ON journal_entries (sourceRecordId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_insulin_presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        onsetMinutes INTEGER NOT NULL,
                        durationMinutes INTEGER NOT NULL,
                        accentColor INTEGER NOT NULL,
                        isBuiltIn INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_insulin_presets_sortOrder ON journal_insulin_presets (sortOrder)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_insulin_presets ADD COLUMN curveJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_insulin_presets ADD COLUMN countsTowardIob INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE journal_insulin_presets SET countsTowardIob = 0 WHERE sortOrder IN (1, 10)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history_deleted_readings (
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY(timestamp, sensorSerial)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_deleted_readings_sensorSerial " +
                        "ON history_deleted_readings (sensorSerial)"
                )
            }
        }
        
        fun getInstance(context: Context): HistoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "glucose_history.db"
                )
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
                .fallbackToDestructiveMigration()  // Fallback if migration chain is broken
                .build().also { INSTANCE = it }
            }
    }
}
