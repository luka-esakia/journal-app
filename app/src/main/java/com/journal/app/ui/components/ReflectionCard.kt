package com.journal.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GEORGIAN = Locale("ka", "GE")
private val DAY_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("d", GEORGIAN)
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", GEORGIAN)
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", GEORGIAN)

/** How much of a source entry is worth showing before the renderer ellipsizes it. */
private const val SOURCE_PREVIEW_LINES = 2

/**
 * The week a reflection covers, e.g. `9–15 მარტი` — or `24 თებ – 1 მარ` across a month boundary.
 *
 * This, not the generated-at timestamp, is what identifies a reflection: two reflections made in
 * the same week are about the same week, and one generated late still belongs to the days it
 * summarises. [Reflection.periodEnd] is exclusive, so the label walks back a millisecond to land
 * on the last day actually covered.
 *
 * Falls back to the generation date for rows with no recorded period — imported backups written
 * before the field existed.
 */
fun formatReflectionPeriod(
    reflection: Reflection,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    if (reflection.periodStart <= 0L || reflection.periodEnd <= reflection.periodStart) {
        return formatShortDateTime(reflection.generatedAt, zone)
    }
    val start = Instant.ofEpochMilli(reflection.periodStart).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(reflection.periodEnd - 1).atZone(zone).toLocalDate()

    return if (start.month == end.month && start.year == end.year) {
        "${start.format(DAY_ONLY)}–${end.format(DAY_MONTH)}"
    } else {
        "${start.format(DAY_MONTH)} – ${end.format(DAY_MONTH)}"
    }
}

/** `მარტი 2026`, for grouping the history list. */
fun formatReflectionMonth(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(MONTH_YEAR)

/**
 * A slim accent-coloured pointer for the timeline.
 *
 * Deliberately **not** a card with the reflection in it. A reflection is a screen and a half of
 * prose; dropping one into the feed every seventh scroll position would bury the entries the
 * journal is actually for. This is a signpost — it says a reflection exists for this week and
 * opens it on tap — and it is accent-tinted rather than matte so it never reads as something the
 * user wrote.
 */
@Composable
fun ReflectionMarker(
    reflection: Reflection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), shape)
            .border(1.dp, accent.copy(alpha = 0.40f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.reflection_marker),
            style = MaterialTheme.typography.labelLarge,
            color = accent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatReflectionPeriod(reflection),
            style = MaterialTheme.typography.labelMedium,
            color = accent.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = stringResource(R.string.reflection_open),
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * One history entry: a single dated line that expands into the full reflection.
 *
 * Collapsed, a reflection is one row instead of a screen and a half, which is the whole point —
 * fifty of them become something you can scan rather than something you scroll past.
 */
@Composable
fun ReflectionRow(
    reflection: Reflection,
    expanded: Boolean,
    onToggle: () -> Unit,
    sources: List<JournalEntry>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatReflectionPeriod(reflection),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // Collapsed, the opening line is the only hint of what is inside, so it is
                    // worth a line of its own rather than being hidden behind the date alone.
                    text = if (expanded) {
                        stringResource(R.string.home_subtitle, reflection.sourceCount())
                    } else {
                        reflection.text.replace('\n', ' ').trim()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                ReflectionBody(
                    reflection = reflection,
                    sources = sources,
                    onDelete = onDelete
                )
            }
        }
    }
}

/**
 * One reflection in full: the text, when and by which model it was made, and its sources.
 *
 * The source list is collapsed by default — it answers "where did this come from", which is
 * asked occasionally and never while reading the reflection itself.
 */
@Composable
fun ReflectionBody(
    reflection: Reflection,
    sources: List<JournalEntry>,
    onDelete: (() -> Unit)? = null,
    sourcesExpanded: Boolean = false,
    onToggleSources: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val recorded = reflection.sourceCount()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = reflection.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.insights_updated,
                        formatShortDateTime(reflection.generatedAt)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                if (reflection.model.isNotBlank()) {
                    Text(
                        text = reflection.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Pre-v3 reflections predate source tracking. Saying so beats an empty list that looks
        // like the reflection came from nothing.
        if (recorded == 0) {
            Text(
                text = stringResource(R.string.insights_sources_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onToggleSources != null) {
                        Modifier.clickable(onClick = onToggleSources)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.insights_sources, recorded),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (sourcesExpanded) {
                    Icons.Outlined.ExpandLess
                } else {
                    Icons.Outlined.ExpandMore
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = sourcesExpanded) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                // Fewer resolved than recorded means entries were deleted after the reflection
                // was written. The link survives that; the entry does not.
                if (sources.size < recorded) {
                    Text(
                        text = stringResource(
                            R.string.insights_sources_missing,
                            recorded - sources.size
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                sources.forEach { source ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatShortDateTime(source.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            maxLines = 1,
                            modifier = Modifier.width(88.dp)
                        )
                        // maxLines + Ellipsis rather than a character cap: the renderer knows
                        // where the text actually stops fitting on this device, so the „…" lands
                        // at the real boundary instead of an arbitrary 90th character.
                        Text(
                            text = source.content.replace('\n', ' ').trim(),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = TextSecondary,
                            maxLines = SOURCE_PREVIEW_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
