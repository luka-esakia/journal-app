package com.journal.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.SectionCard
import com.journal.app.ui.components.TagChip
import com.journal.app.ui.components.formatShortDateTime
import com.journal.app.ui.theme.CardBorder
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary

/** AI output: semantic topic tags across all entries, and the weekly reflections. */
@Composable
fun InsightsScreen(
    viewModel: JournalViewModel,
    /** Filters the timeline by a tag and switches to it — a tag here is a shortcut, not a view. */
    onOpenTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val tagCounts by viewModel.tagCounts.collectAsStateWithLifecycle()
    val pending by viewModel.unanalyzedCount.collectAsStateWithLifecycle()
    val reflections by viewModel.reflections.collectAsStateWithLifecycle()
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val aiUsable = aiSettings.isUsable()
    val latest = reflections.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatTile(
                    value = entries.size.toString(),
                    label = stringResource(R.string.insights_stat_entries),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = viewModel.entriesThisWeek().toString(),
                    label = stringResource(R.string.insights_stat_week),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = tagCounts.size.toString(),
                    label = stringResource(R.string.insights_stat_tags),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (!aiUsable) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.insights_need_key),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.insights_tags)) {
                if (tagCounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.insights_tags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                } else {
                    TagCloud(tagCounts = tagCounts, onTagClick = onOpenTag)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.insights_tags_tappable),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }

                // Always rendered and always pressable: a disabled button is indistinguishable
                // from a broken one, so let the press happen and explain the failure instead.
                Spacer(Modifier.height(14.dp))
                if (pending > 0) {
                    Text(
                        text = stringResource(R.string.insights_pending, pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::analyzePending,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.insights_analyze),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.insights_weekly)) {
                if (latest == null) {
                    Text(
                        text = stringResource(R.string.insights_weekly_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                } else {
                    ReflectionBody(
                        reflection = latest,
                        sources = viewModel.sourceEntriesOf(latest),
                        onDelete = { viewModel.deleteReflection(latest) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = viewModel::generateWeeklyReflection,
                    enabled = !busy,
                    // Sized by content, never pinned: "კვირის რეფლექსიის გენერაცია" wraps to two
                    // lines on a phone and a fixed height clips the second one.
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = CardBorder,
                        disabledContentColor = TextTertiary
                    )
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.insights_working),
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Insights,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.insights_generate),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Reflections used to overwrite each other in a single preference slot; now each one is
        // its own row, so the older ones are worth showing rather than silently discarding.
        val history = reflections.drop(1)
        if (history.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.insights_history, history.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(count = history.size, key = { history[it].id }) { index ->
                val reflection = history[index]
                SectionCard {
                    ReflectionBody(
                        reflection = reflection,
                        sources = viewModel.sourceEntriesOf(reflection),
                        onDelete = { viewModel.deleteReflection(reflection) }
                    )
                }
            }
        }
    }
}

/**
 * One reflection: the text, when and by which model it was made, and the entries behind it.
 *
 * The source list is collapsed by default. It is the answer to "where did this come from" —
 * asked occasionally, and never while reading the reflection itself.
 */
@Composable
private fun ReflectionBody(
    reflection: Reflection,
    sources: List<JournalEntry>,
    onDelete: () -> Unit
) {
    var sourcesOpen by remember(reflection.id) { mutableStateOf(false) }
    val recorded = reflection.sourceCount()

    Column(modifier = Modifier.fillMaxWidth()) {
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
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            // Pre-v3 reflections predate source tracking. Saying so beats an empty list that
            // looks like the reflection came from nothing.
            recorded == 0 -> Text(
                text = stringResource(R.string.insights_sources_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sourcesOpen = !sourcesOpen }
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
                        imageVector = if (sourcesOpen) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ExpandMore
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = sourcesOpen) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        // Fewer resolved than recorded means entries were deleted after the
                        // reflection was written. The link survives that; the entry does not.
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
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    text = formatShortDateTime(source.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary,
                                    modifier = Modifier.width(92.dp)
                                )
                                Text(
                                    text = source.content.replace('\n', ' ').take(90),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCloud(
    tagCounts: List<Pair<String, Int>>,
    onTagClick: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tagCounts.forEach { (tag, count) ->
            TagChip(tag = tag, count = count, onClick = { onTagClick(tag) })
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    SectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}
