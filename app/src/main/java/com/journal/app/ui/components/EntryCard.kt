package com.journal.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.ui.theme.CardBorder
import com.journal.app.ui.theme.CardCornerRadius
import com.journal.app.ui.theme.CardSurface
import com.journal.app.ui.theme.MindJournalTheme
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GEORGIAN_LOCALE = Locale("ka", "GE")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", GEORGIAN_LOCALE)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, EEEE", GEORGIAN_LOCALE)
private val SHORT_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", GEORGIAN_LOCALE)

fun formatEntryTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(TIME_FORMAT)

fun formatDayHeader(date: LocalDate): String = date.format(DAY_FORMAT)

fun formatShortDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(SHORT_DATE_FORMAT)

/**
 * One journal entry: matte card, hairline border, 14dp corners.
 *
 * The prompt (when the entry came from a notification) is shown above the text in the accent hue,
 * so the timeline reads as a conversation rather than a wall of paragraphs.
 */
@Composable
fun EntryCard(
    entry: JournalEntry,
    onDelete: (JournalEntry) -> Unit,
    onEdit: (JournalEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Tag taps filter the feed. Null leaves the chips inert (previews, source lists). */
    onTagClick: ((String) -> Unit)? = null,
    /** The tag the feed is currently filtered by, highlighted so the chip reads as a toggle. */
    activeTag: String? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit(entry) },
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatEntryTime(entry.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                if (entry.isFromNotification()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.NotificationsNone,
                        contentDescription = stringResource(R.string.source_notification),
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (entry.wasEdited()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.entry_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onDelete(entry) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!entry.prompt.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp)
            )

            val tags = entry.tagList()
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TagRow(tags = tags, onTagClick = onTagClick, activeTag = activeTag)
            }
        }
    }
}

/**
 * The shared card shell: matte surface, hairline border, 14dp corners, optional title row.
 * Every settings/insights block is built from this so the whole app has one card language.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    onTagClick: ((String) -> Unit)? = null,
    activeTag: String? = null
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            TagChip(
                tag = tag,
                selected = tag.equals(activeTag, ignoreCase = true),
                onClick = onTagClick?.let { click -> { click(tag) } }
            )
        }
    }
}

/**
 * Accent-tinted pill used for AI topic tags, optionally with an occurrence count.
 *
 * A chip with an [onClick] filters the feed to that topic; [selected] inverts the fill so the
 * tag currently being filtered on is obvious from inside the results, where every visible card
 * carries it and a subtle highlight would be lost.
 *
 * [onRemove] turns the chip into an editable one — used by the tag editor, not the timeline,
 * because a delete affordance on every card would be far too easy to hit while scrolling.
 */
@Composable
fun TagChip(
    tag: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(999.dp)
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else accent

    Row(
        modifier = modifier
            .background(if (selected) accent else accent.copy(alpha = 0.12f), shape)
            .border(1.dp, accent.copy(alpha = if (selected) 1f else 0.32f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f)
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.tag_remove, tag),
                    tint = content,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * Add / remove the topic tags on one entry.
 *
 * Tags were read-only until now: the LLM produced them and a wrong one could only be fixed by
 * rewriting the entry and hoping for a different answer. Since the feed can be filtered by tag,
 * a wrong tag is no longer cosmetic — it misfiles the entry — so they have to be editable.
 *
 * The field commits on both Done and the **+** button, and accepts a comma-separated run so
 * pasting `#ძილი, #ოჯახი` adds two rather than one malformed tag. Normalisation (the leading
 * `#`, whitespace, length) happens in [JournalEntry.normalizeTag] so a hand-typed tag and a
 * generated one are stored identically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun commit() {
        val added = draft
            .split(JournalEntry.TAG_SEPARATOR, "\n")
            .mapNotNull(JournalEntry::normalizeTag)
        if (added.isNotEmpty()) {
            onTagsChange((tags + added).distinctBy { it.lowercase() })
        }
        draft = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (tags.isEmpty()) {
            Text(
                text = stringResource(R.string.tag_editor_empty),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        onRemove = { onTagsChange(tags.filterNot { it == tag }) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.tag_editor_hint),
                    color = TextTertiary
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = {
                if (draft.isNotBlank()) {
                    IconButton(onClick = ::commit) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.tag_add),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun EntryCardPreview() {
    MindJournalTheme {
        EntryCard(
            entry = JournalEntry(
                id = 1,
                content = "დღეს ბევრი შეხვედრა იყო და საღამომდე თავი დაღლილად მეგრძნო. " +
                    "მაგრამ ერთი საუბარი მართლა ღირებული აღმოჩნდა.",
                createdAt = System.currentTimeMillis(),
                prompt = "რამ წაგართვა ენერგია დღეს?",
                tags = "#მუშაობა,#დაღლილობა,#ადამიანები",
                source = JournalEntry.SOURCE_NOTIFICATION,
                analyzed = true
            ),
            onDelete = {},
            onEdit = {}
        )
    }
}
