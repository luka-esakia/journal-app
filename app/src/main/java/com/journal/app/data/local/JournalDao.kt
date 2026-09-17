package com.journal.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Insert
    suspend fun insert(entry: JournalEntry): Long

    @Update
    suspend fun update(entry: JournalEntry)

    @Delete
    suspend fun delete(entry: JournalEntry)

    @Query("SELECT * FROM journal_entries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries ORDER BY created_at DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<JournalEntry>

    @Query("SELECT * FROM journal_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): JournalEntry?

    @Query(
        "SELECT * FROM journal_entries WHERE created_at >= :from AND created_at < :to " +
            "ORDER BY created_at ASC"
    )
    suspend fun between(from: Long, to: Long): List<JournalEntry>

    @Query("SELECT * FROM journal_entries WHERE analyzed = 0 ORDER BY created_at DESC LIMIT :limit")
    suspend fun unanalyzed(limit: Int): List<JournalEntry>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE analyzed = 0")
    fun observeUnanalyzedCount(): Flow<Int>

    @Query("UPDATE journal_entries SET tags = :tags, analyzed = 1 WHERE id = :id")
    suspend fun applyTags(id: Long, tags: String)

    @Query("UPDATE journal_entries SET analyzed = 1 WHERE id = :id")
    suspend fun markAnalyzed(id: Long)

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAll()
}
