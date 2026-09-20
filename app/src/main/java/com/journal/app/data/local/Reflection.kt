package com.journal.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One generated AI reflection, together with the exact entries it was generated from.
 *
 * ### Why the source ids are a plain list and not a foreign key
 * A reflection is a *record of what was sent to the model*. If a source entry is later deleted, a
 * real foreign key would either block the delete or cascade the link away — and the export would
 * then claim the reflection came from fewer entries than it did. Storing the ids as an opaque,
 * comma separated list keeps that provenance intact no matter what happens to the journal
 * afterwards, which is the entire point of linking them. It also mirrors how [JournalEntry] stores
 * its tags, so the schema has one idiom rather than two.
 */
@Entity(
    tableName = "reflections",
    indices = [Index(value = ["generated_at"])]
)
data class Reflection(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** The reflection text as the model produced it. */
    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "generated_at")
    val generatedAt: Long,

    /** Inclusive start of the period the reflection covers. */
    @ColumnInfo(name = "period_start")
    val periodStart: Long,

    /** Exclusive end of the period the reflection covers. */
    @ColumnInfo(name = "period_end")
    val periodEnd: Long,

    /** Comma separated `journal_entries.id` values that were fed to the model. */
    @ColumnInfo(name = "source_entry_ids")
    val sourceEntryIds: String = "",

    /** The model slug that produced this text, so an export says who wrote what. */
    @ColumnInfo(name = "model")
    val model: String = ""
) {

    /** Source ids as a clean list; a function so Room never mistakes it for a column. */
    fun sourceIdList(): List<Long> =
        sourceEntryIds.split(ID_SEPARATOR)
            .mapNotNull { it.trim().toLongOrNull() }

    fun sourceCount(): Int = sourceIdList().size

    companion object {
        const val ID_SEPARATOR = ","

        fun joinIds(ids: List<Long>): String =
            ids.distinct().sorted().joinToString(ID_SEPARATOR)
    }
}

@Dao
interface ReflectionDao {

    @Insert
    suspend fun insert(reflection: Reflection): Long

    @Delete
    suspend fun delete(reflection: Reflection)

    /** Newest first — the Insights screen shows the latest and keeps the rest as history. */
    @Query("SELECT * FROM reflections ORDER BY generated_at DESC")
    fun observeAll(): Flow<List<Reflection>>

    @Query("SELECT COUNT(*) FROM reflections")
    suspend fun count(): Int

    /** Duplicate probe for imports: same instant and same text means the same reflection. */
    @Query("SELECT COUNT(*) FROM reflections WHERE generated_at = :generatedAt AND text = :text")
    suspend fun countMatching(generatedAt: Long, text: String): Int

    @Query("DELETE FROM reflections")
    suspend fun deleteAll()
}
