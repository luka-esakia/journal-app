package com.journal.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
import com.journal.app.ui.FeedFilter
import com.journal.app.ui.FeedItem
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.EntryCard
import com.journal.app.ui.components.JournalCalendar
import com.journal.app.ui.components.ReflectionBody
import com.journal.app.ui.components.ReflectionMarker
import com.journal.app.ui.components.SectionCard
import com.journal.app.ui.components.TagEditor
import com.journal.app.ui.components.formatDayHeader
import com.journal.app.ui.components.formatReflectionPeriod
import com.journal.app.ui.theme.CardSurface
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Timeline of every entry, newest first, grouped by day, plus search, filters and quick-add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier
) {
    val allEntries by viewModel.entries.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val dayCounts by viewModel.dayCounts.collectAsStateWithLifecycle()
    val quickPrompt by viewModel.quickAddPrompt.collectAsStateWithLifecycle()
    val promptLoading by viewModel.promptLoading.collectAsStateWithLifecycle()
    val sheetOpen by viewModel.composerOpen.collectAsStateWithLifecycle()

    var draft by rememberSaveable { mutableStateOf("") }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<JournalEntry?>(null) }
    var editing by remember { mutableStateOf<JournalEntry?>(null) }
    var readingReflection by remember { mutableStateOf<Reflection?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            SearchAndFilterBar(
                filter = filter,
                calendarOpen = calendarOpen,
                onQueryChange = viewModel::setSearchQuery,
                onToggleCalendar = { calendarOpen = !calendarOpen },
                onClearFilter = {
                    viewModel.clearFilter()
                    calendarOpen = false
                },
                onClearTag = { viewModel.setTagFilter(null) },
                onClearDate = { viewModel.setDateFilter(null) }
            )

            AnimatedVisibility(visible = calendarOpen) {
                SectionCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    JournalCalendar(
                        dayCounts = dayCounts,
                        selectedDate = filter.date,
                        onSelectDate = viewModel::setDateFilter
                    )
                }
            }

            when {
                // "Nothing written yet" and "nothing matches" are different problems and need
                // different answers — offering to clear a filter you do not have is noise.
                allEntries.isEmpty() -> EmptyTimeline(modifier = Modifier.fillMaxSize())

                feed.isEmpty() -> NoResults(
                    onClear = {
                        viewModel.clearFilter()
                        calendarOpen = false
                    },
                    modifier = Modifier.fillMaxSize()
                )

                else -> Timeline(
                    items = feed,
                    // A relevance-ranked list must not be chopped into day groups: the headers
                    // would claim an order the list does not have.
                    grouped = !filter.isSearching(),
                    activeTag = filter.tag,
                    onDelete = { pendingDelete = it },
                    onEdit = { editing = it },
                    onTagClick = viewModel::toggleTagFilter,
                    onOpenReflection = { readingReflection = it }
                )
            }
        }

        FloatingActionButton(
            onClick = { viewModel.openComposer() },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.fab_add)
            )
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.closeComposer()
                draft = ""
            },
            sheetState = sheetState,
            containerColor = CardSurface,
            dragHandle = null
        ) {
            QuickAddSheet(
                prompt = quickPrompt,
                promptLoading = promptLoading,
                draft = draft,
                onDraftChange = { draft = it },
                onPickFromBank = viewModel::pickPromptFromBank,
                onGenerateAi = viewModel::generateAiPrompt,
                onClearPrompt = viewModel::clearPrompt,
                onCancel = {
                    viewModel.closeComposer()
                    draft = ""
                },
                onSave = {
                    // addEntry closes the sheet itself once the write lands.
                    viewModel.addEntry(draft, quickPrompt)
                    draft = ""
                }
            )
        }
    }

    editing?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CardSurface,
            dragHandle = null
        ) {
            EditEntrySheet(
                entry = entry,
                onCancel = { editing = null },
                onSave = { text, keepPrompt, tags ->
                    val bodyChanged = text != entry.content ||
                        keepPrompt != (entry.prompt != null)
                    if (!bodyChanged && tags != null) {
                        // Tags-only edit. Routing this through editEntry would stamp `edited_at`
                        // and mark the card „რედაქტირებული" for a change to its topics, which
                        // is not what that badge claims.
                        viewModel.updateTags(entry.id, tags)
                    } else {
                        viewModel.editEntry(
                            id = entry.id,
                            content = text,
                            prompt = entry.prompt.takeIf { keepPrompt },
                            tags = tags
                        )
                    }
                    editing = null
                }
            )
        }
    }

    // Tapping a timeline marker reads the reflection here rather than jumping to ანალიზი, which
    // would throw away the scroll position the user came from.
    readingReflection?.let { reflection ->
        var sourcesOpen by remember(reflection.id) { mutableStateOf(false) }
        ModalBottomSheet(
            onDismissRequest = { readingReflection = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CardSurface,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.reflection_marker),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatReflectionPeriod(reflection),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(14.dp))
                ReflectionBody(
                    reflection = reflection,
                    sources = viewModel.sourceEntriesOf(reflection),
                    sourcesExpanded = sourcesOpen,
                    onToggleSources = { sourcesOpen = !sourcesOpen }
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { readingReflection = null }) {
                        Text(stringResource(R.string.action_close), color = TextSecondary)
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    // Was a silent take(120); the renderer knows where the text really stops.
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry)
                    pendingDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * The search field, the calendar toggle, and a chip per active filter.
 *
 * Each dimension gets its own dismissible chip rather than one blanket "clear": having narrowed
 * to `#ძილი` on the 3rd, dropping just the date is the common next move, and a single clear
 * would throw away the part the user wanted to keep. **გასუფთავება** is offered as well, for
 * when everything should go.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchAndFilterBar(
    filter: FeedFilter,
    calendarOpen: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleCalendar: () -> Unit,
    onClearFilter: () -> Unit,
    onClearTag: () -> Unit,
    onClearDate: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.home_search_hint),
                        color = TextTertiary
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.home_search_clear),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onToggleCalendar, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(R.string.home_calendar_toggle),
                    tint = if (calendarOpen || filter.date != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        TextSecondary
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (!filter.isActive()) return@Column

        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            filter.tag?.let { tag ->
                DismissibleFilterChip(label = tag, onDismiss = onClearTag)
            }
            filter.date?.let { date ->
                DismissibleFilterChip(label = formatDayHeader(date), onDismiss = onClearDate)
            }
            FilterChip(
                selected = false,
                onClick = onClearFilter,
                label = {
                    Text(
                        text = stringResource(R.string.home_filter_clear),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(999.dp),
                colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleFilterChip(label: String, onDismiss: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onDismiss,
        label = {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.home_filter_clear),
                modifier = Modifier.size(14.dp)
            )
        },
        shape = RoundedCornerShape(999.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun Timeline(
    items: List<FeedItem>,
    grouped: Boolean,
    activeTag: String?,
    onDelete: (JournalEntry) -> Unit,
    onEdit: (JournalEntry) -> Unit,
    onTagClick: (String) -> Unit,
    onOpenReflection: (Reflection) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    // Items arrive newest-first; grouping preserves that order.
    val groups = remember(items, grouped) {
        if (grouped) {
            items.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        } else {
            emptyMap()
        }
    }
    val today = remember(items) { LocalDate.now(zone) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (grouped) {
            groups.forEach { (date, dayItems) ->
                item(key = "header-$date") {
                    DayHeader(
                        label = when (date) {
                            today -> stringResource(R.string.home_today)
                            today.minusDays(1) -> stringResource(R.string.home_yesterday)
                            else -> formatDayHeader(date)
                        },
                        // A reflection marker is not an entry; counting it would overstate
                        // what was actually written that day.
                        count = dayItems.count { it is FeedItem.Entry }
                    )
                }
                items(items = dayItems, key = { it.key }) { item ->
                    FeedRow(
                        item = item,
                        activeTag = activeTag,
                        onDelete = onDelete,
                        onEdit = onEdit,
                        onTagClick = onTagClick,
                        onOpenReflection = onOpenReflection
                    )
                }
            }
        } else {
            item(key = "result-count") {
                DayHeader(
                    label = stringResource(R.string.home_results),
                    count = items.size
                )
            }
            items(items = items, key = { it.key }) { item ->
                FeedRow(
                    item = item,
                    activeTag = activeTag,
                    onDelete = onDelete,
                    onEdit = onEdit,
                    onTagClick = onTagClick,
                    onOpenReflection = onOpenReflection
                )
            }
        }
    }
}

@Composable
private fun FeedRow(
    item: FeedItem,
    activeTag: String?,
    onDelete: (JournalEntry) -> Unit,
    onEdit: (JournalEntry) -> Unit,
    onTagClick: (String) -> Unit,
    onOpenReflection: (Reflection) -> Unit
) {
    when (item) {
        is FeedItem.Entry -> EntryCard(
            entry = item.entry,
            onDelete = onDelete,
            onEdit = onEdit,
            onTagClick = onTagClick,
            activeTag = activeTag
        )

        is FeedItem.ReflectionMark -> ReflectionMarker(
            reflection = item.reflection,
            onClick = { onOpenReflection(item.reflection) }
        )
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_subtitle, count),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

@Composable
private fun EmptyTimeline(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.EditNote,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

/** The journal is not empty, the filter just matched nothing. */
@Composable
private fun NoResults(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_no_results),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClear) {
            Text(
                text = stringResource(R.string.home_filter_clear),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Edits an existing entry: its text, whether it keeps its prompt, and its tags.
 *
 * Tags are only sent back when the user actually touched them. Left alone, saving clears them
 * and re-queues the entry for analysis, since topics derived from the old wording would linger;
 * touched, they are kept verbatim and the LLM is not allowed to overwrite the correction. The
 * original prompt can be kept or dropped, but not swapped — rerolling the question of an entry
 * already written against it only causes confusion.
 */
@Composable
private fun EditEntrySheet(
    entry: JournalEntry,
    onCancel: () -> Unit,
    onSave: (String, Boolean, List<String>?) -> Unit
) {
    var text by rememberSaveable(entry.id) { mutableStateOf(entry.content) }
    var keepPrompt by rememberSaveable(entry.id) { mutableStateOf(entry.prompt != null) }
    // Plain remember, not rememberSaveable: a List is not Bundle-storable, and the sheet's own
    // open/closed state is not saved either, so there is nothing for it to be restored into.
    var tags by remember(entry.id) { mutableStateOf(entry.tagList()) }
    var tagsTouched by remember(entry.id) { mutableStateOf(false) }

    val dirty = text != entry.content ||
        keepPrompt != (entry.prompt != null) ||
        tagsTouched

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.dialog_edit_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!entry.prompt.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = if (keepPrompt) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        TextTertiary
                    },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { keepPrompt = !keepPrompt },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (keepPrompt) {
                            Icons.Outlined.Close
                        } else {
                            Icons.Outlined.Undo
                        },
                        contentDescription = stringResource(R.string.dialog_prompt_remove),
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default
            )
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.tag_editor_title),
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        TagEditor(
            tags = tags,
            onTagsChange = {
                tags = it
                tagsTouched = true
            }
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (tagsTouched) R.string.dialog_edit_keep_tags else R.string.dialog_edit_retag
            ),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { onSave(text, keepPrompt, tags.takeIf { tagsTouched }) },
                enabled = text.isNotBlank() && dirty
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    color = if (text.isNotBlank() && dirty) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        TextTertiary
                    }
                )
            }
        }
    }
}

/**
 * Quick-add. The entry itself is the whole point, so the sheet opens with **no prompt** and an
 * empty body. A prompt is opt-in: 🎲 draws one from the local bank (free, instant), ✨ asks the
 * LLM for one informed by recent topics — and a notification body tap arrives with its own
 * question already in place.
 */
@Composable
private fun QuickAddSheet(
    prompt: String?,
    promptLoading: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onPickFromBank: () -> Unit,
    onGenerateAi: () -> Unit,
    onClearPrompt: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dialog_new_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onPickFromBank, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Casino,
                    contentDescription = stringResource(R.string.dialog_prompt_bank),
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onGenerateAi,
                enabled = !promptLoading,
                modifier = Modifier.size(38.dp)
            ) {
                if (promptLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = stringResource(R.string.dialog_prompt_ai),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (prompt == null) {
            Text(
                text = stringResource(R.string.dialog_no_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 6.dp)
                )
                IconButton(onClick = onClearPrompt, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.dialog_prompt_remove),
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.dialog_hint),
                    color = TextTertiary
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default
            )
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onSave,
                enabled = draft.isNotBlank()
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    color = if (draft.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        TextTertiary
                    }
                )
            }
        }
    }
}
