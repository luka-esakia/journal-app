package com.journal.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single journal entry.
 *
 * Tags are persisted as a comma separated string instead of a relation table: the AI produces a
 * handful of Georgian topic tags per entry and we only ever need them for display and counting,
 * so a join table would add migration surface without buying anything.
 */
@Entity(
    tableName = "journal_entries",
    indices = [Index(value = ["created_at"]), Index(value = ["analyzed"])]
)
data class JournalEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** The user's text. Never leaves the device unless AI analysis is explicitly enabled. */
    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** The prompt the entry was written in answer to, when it came from a notification. */
    @ColumnInfo(name = "prompt")
    val prompt: String? = null,

    /** Comma separated Georgian topic tags, e.g. `#მუშაობა,#დაღლილობა`. */
    @ColumnInfo(name = "tags")
    val tags: String = "",

    @ColumnInfo(name = "source")
    val source: String = SOURCE_APP,

    /** True once the LLM has produced tags (or failed permanently) for this entry. */
    @ColumnInfo(name = "analyzed")
    val analyzed: Boolean = false
) {

    /** Tags as a clean list; declared as a function so Room never mistakes it for a column. */
    fun tagList(): List<String> =
        tags.split(TAG_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun isFromNotification(): Boolean = source == SOURCE_NOTIFICATION

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_NOTIFICATION = "notification"
        const val TAG_SEPARATOR = ","

        fun joinTags(tags: List<String>): String =
            tags.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(TAG_SEPARATOR)
    }
}
