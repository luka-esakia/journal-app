package com.journal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JournalEntry::class, Reflection::class],
    version = 3,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {

    abstract fun journalDao(): JournalDao

    abstract fun reflectionDao(): ReflectionDao

    companion object {
        private const val DB_NAME = "mind_journal.db"

        /**
         * v1 → v2: adds `edited_at`.
         *
         * A real migration rather than a destructive fallback — by this point people have
         * journals on their phones, and dropping the table to add one nullable column would
         * silently delete them.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN edited_at INTEGER")
            }
        }

        /**
         * v2 → v3: adds the `reflections` table.
         *
         * Reflections used to live in [PreferenceManager] as a single overwritten string, so
         * each new one erased the last and nothing recorded which entries produced it. The
         * statements below must match what Room generates for [Reflection] exactly — including
         * the absence of SQL defaults — or the schema check fails on first open. Existing
         * journals are untouched: this only creates a new table.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reflections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`generated_at` INTEGER NOT NULL, " +
                        "`period_start` INTEGER NOT NULL, " +
                        "`period_end` INTEGER NOT NULL, " +
                        "`source_entry_ids` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reflections_generated_at` " +
                        "ON `reflections` (`generated_at`)"
                )
            }
        }

        @Volatile
        private var instance: JournalDatabase? = null

        /**
         * Process-wide singleton. Broadcast receivers get their own short lived [Context], so the
         * database must never be tied to an Activity or to the Application object's lifetime.
         */
        fun getInstance(context: Context): JournalDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): JournalDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JournalDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
