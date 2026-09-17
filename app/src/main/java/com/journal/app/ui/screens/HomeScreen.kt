package com.journal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.R
import com.journal.app.data.local.JournalEntry
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.EntryCard
import com.journal.app.ui.components.formatDayHeader
import com.journal.app.ui.theme.CardSurface
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Timeline of every entry, newest first, grouped by day, plus the quick-add sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val quickPrompt by viewModel.quickAddPrompt.collectAsStateWithLifecycle()
    val promptLoading by viewModel.promptLoading.collectAsStateWithLifecycle()

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<JournalEntry?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = modifier.fillMaxSize()) {

        if (entries.isEmpty()) {
            EmptyTimeline(modifier = Modifier.fillMaxSize())
        } else {
            Timeline(
                entries = entries,
                onDelete = { pendingDelete = it }
            )
        }

        FloatingActionButton(
            onClick = { sheetOpen = true },
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
                sheetOpen = false
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
                    sheetOpen = false
                    draft = ""
                },
                onSave = {
                    viewModel.addEntry(draft, quickPrompt)
                    draft = ""
                    sheetOpen = false
                }
            )
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Text(
                    text = entry.content.take(120),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
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

@Composable
private fun Timeline(
    entries: List<JournalEntry>,
    onDelete: (JournalEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    // Entries arrive newest-first from Room; grouping preserves that order.
    val grouped = remember(entries) {
        entries.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
    }
    val today = remember(entries) { LocalDate.now(zone) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        grouped.forEach { (date, dayEntries) ->
            item(key = "header-$date") {
                DayHeader(
                    label = when (date) {
                        today -> stringResource(R.string.home_today)
                        today.minusDays(1) -> stringResource(R.string.home_yesterday)
                        else -> formatDayHeader(date)
                    },
                    count = dayEntries.size
                )
            }
            items(items = dayEntries, key = { it.id }) { entry ->
                EntryCard(entry = entry, onDelete = onDelete)
            }
        }
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

/**
 * Quick-add. The entry itself is the whole point, so the sheet opens with **no prompt** and an
 * empty body. A prompt is opt-in: 🎲 draws one from the local bank (free, instant), ✨ asks the
 * LLM for one informed by recent topics.
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
