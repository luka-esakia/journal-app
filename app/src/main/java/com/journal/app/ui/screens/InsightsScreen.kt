package com.journal.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.ReflectionBody
import com.journal.app.ui.components.ReflectionRow
import com.journal.app.ui.components.SectionCard
import com.journal.app.ui.components.TagChip
import com.journal.app.ui.components.formatReflectionMonth
import com.journal.app.ui.theme.CardBorder
import com.journal.app.ui.theme.MatteBackground
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

    var historyOpen by remember { mutableStateOf(false) }
    // One expanded history row at a time, by id rather than index so the selection survives a
    // new reflection arriving at the top of the list.
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var latestSourcesOpen by remember { mutableStateOf(false) }
    var allTagsShown by remember { mutableStateOf(false) }

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
                    val hidden = (tagCounts.size - TAG_CLOUD_PREVIEW).coerceAtLeast(0)
                    TagCloud(
                        tagCounts = if (allTagsShown) {
                            tagCounts
                        } else {
                            tagCounts.take(TAG_CLOUD_PREVIEW)
                        },
                        onTagClick = onOpenTag
                    )
                    if (hidden > 0) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { allTagsShown = !allTagsShown },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (allTagsShown) {
                                    stringResource(R.string.insights_tags_less)
                                } else {
                                    stringResource(R.string.insights_tags_more, hidden)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
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
                        onDelete = { viewModel.deleteReflection(latest) },
                        sourcesExpanded = latestSourcesOpen,
                        onToggleSources = { latestSourcesOpen = !latestSourcesOpen }
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
        // its own row. Only a handful are previewed here — rendering all of them in full turned
        // this tab into an archive you had to scroll past to reach anything else.
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
            val preview = history.take(HISTORY_PREVIEW)
            items(count = preview.size, key = { preview[it].id }) { index ->
                val reflection = preview[index]
                ReflectionRow(
                    reflection = reflection,
                    expanded = expandedId == reflection.id,
                    onToggle = {
                        expandedId = if (expandedId == reflection.id) null else reflection.id
                    },
                    sources = viewModel.sourceEntriesOf(reflection),
                    onDelete = { viewModel.deleteReflection(reflection) }
                )
            }
            if (history.size > HISTORY_PREVIEW) {
                item {
                    TextButton(
                        onClick = { historyOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.insights_see_all, history.size),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (historyOpen) {
        ReflectionHistorySheet(
            reflections = reflections,
            sourcesOf = viewModel::sourceEntriesOf,
            onDelete = viewModel::deleteReflection,
            onDismiss = { historyOpen = false }
        )
    }
}

/**
 * The whole reflection archive, grouped by month.
 *
 * A separate surface rather than more rows on the tab: the number of reflections grows without
 * bound and the tab's job is "what is true now". Here every row is collapsed by default, so the
 * list stays scannable at fifty items in a way fifty full reflections never could be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflectionHistorySheet(
    reflections: List<Reflection>,
    sourcesOf: (Reflection) -> List<JournalEntry>,
    onDelete: (Reflection) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val byMonth = remember(reflections) {
        reflections.groupBy { formatReflectionMonth(it.generatedAt) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MatteBackground,
        dragHandle = null
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.insights_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close), color = TextSecondary)
                    }
                }
            }

            byMonth.forEach { (month, inMonth) ->
                item(key = "month-$month") {
                    Text(
                        text = month,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                items(count = inMonth.size, key = { inMonth[it].id }) { index ->
                    val reflection = inMonth[index]
                    ReflectionRow(
                        reflection = reflection,
                        expanded = expandedId == reflection.id,
                        onToggle = {
                            expandedId = if (expandedId == reflection.id) null else reflection.id
                        },
                        sources = sourcesOf(reflection),
                        onDelete = { onDelete(reflection) }
                    )
                }
            }
        }
    }
}

/** Past reflections previewed on the tab itself before the archive takes over. */
private const val HISTORY_PREVIEW = 3

/**
 * Tags shown before the cloud is truncated.
 *
 * The list is sorted by frequency, and the LLM emits 2–4 tags per entry, so a year in there are
 * plausibly a couple of hundred distinct ones with a long tail used exactly once. Twelve covers
 * what the user actually writes about; the rest are one tap away.
 */
private const val TAG_CLOUD_PREVIEW = 12

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
