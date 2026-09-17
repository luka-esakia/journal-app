package com.journal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [JournalEntry::class],
    version = 1,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {

    abstract fun journalDao(): JournalDao

    companion object {
        private const val DB_NAME = "mind_journal.db"

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
                .fallbackToDestructiveMigration()
                .build()
    }
}
