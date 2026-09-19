package com.journal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JournalEntry::class],
    version = 2,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {

    abstract fun journalDao(): JournalDao

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
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
