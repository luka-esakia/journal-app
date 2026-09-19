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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
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
    modifier: Modifier = Modifier
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
                TagRow(tags = tags)
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
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag -> TagChip(tag = tag) }
    }
}

/** Accent-tinted pill used for AI topic tags, optionally with an occurrence count. */
@Composable
fun TagChip(
    tag: String,
    count: Int? = null,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.7f)
            )
        }
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
