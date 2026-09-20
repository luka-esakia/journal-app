package com.journal.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.BuildConfig
import com.journal.app.R
import com.journal.app.data.export.JournalExporter
import com.journal.app.data.export.JournalImporter
import com.journal.app.data.local.AiSettings
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.SectionCard
import com.journal.app.ui.lock.AppLock
import com.journal.app.ui.theme.AccentColor
import com.journal.app.ui.theme.CardSurface
import com.journal.app.ui.theme.OnAccent
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary

/**
 * API key (encrypted at rest), model picker, cost routing, accent palette, export, and the
 * destructive data action.
 *
 * Everything in the AI block is a *draft* until the explicit save button at the bottom of that
 * card is pressed — the previous inline check-icon affordances were easy to miss, which made
 * edits look applied when they had never been persisted.
 */
@Composable
fun SettingsScreen(
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val lockAvailable = remember { AppLock.canLock(context) }

    var keyDraft by rememberSaveable(aiSettings.apiKey) { mutableStateOf(aiSettings.apiKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var tagModelDraft by rememberSaveable(aiSettings.tagModel) {
        mutableStateOf(aiSettings.tagModel)
    }
    var reflectionModelDraft by rememberSaveable(aiSettings.reflectionModel) {
        mutableStateOf(aiSettings.reflectionModel)
    }
    var lowPriorityDraft by rememberSaveable(aiSettings.lowPriority) {
        mutableStateOf(aiSettings.lowPriority)
    }
    var customSlug by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRetag by remember { mutableStateOf(false) }

    val dirty = keyDraft != aiSettings.apiKey ||
        tagModelDraft != aiSettings.tagModel ||
        reflectionModelDraft != aiSettings.reflectionModel ||
        lowPriorityDraft != aiSettings.lowPriority

    val exportJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JournalExporter.JSON_MIME)
    ) { uri -> uri?.let { viewModel.exportTo(it, asJson = true) } }

    val exportMarkdown = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JournalExporter.MARKDOWN_MIME)
    ) { uri -> uri?.let { viewModel.exportTo(it, asJson = false) } }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFrom(it) } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ------------------------------------------------------------ accent
        item {
            SectionCard(title = stringResource(R.string.settings_accent)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AccentColor.entries.forEach { option ->
                        AccentSwatch(
                            option = option,
                            selected = option == accent,
                            onClick = { viewModel.setAccent(option) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(accent.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent.color
                )
            }
        }

        // ------------------------------------------------------------ security
        item {
            SectionCard(title = stringResource(R.string.settings_security)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_app_lock),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                if (lockAvailable) {
                                    R.string.settings_app_lock_hint
                                } else {
                                    R.string.settings_app_lock_unavailable
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = appLockEnabled && lockAvailable,
                        enabled = lockAvailable,
                        onCheckedChange = viewModel::setAppLockEnabled,
                        colors = journalSwitchColors()
                    )
                }
            }
        }

        // ---------------------------------------------------------------- ai
        item {
            SectionCard(title = stringResource(R.string.settings_ai)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_ai_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = aiSettings.enabled,
                        onCheckedChange = viewModel::setAiEnabled,
                        colors = journalSwitchColors()
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.settings_api_key_hint),
                            color = TextTertiary
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = stringResource(
                                    if (keyVisible) R.string.settings_hide else R.string.settings_show
                                ),
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (aiSettings.apiKey.isBlank()) {
                            R.string.settings_api_key_empty
                        } else {
                            R.string.settings_api_key_stored
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                Spacer(Modifier.height(18.dp))

                // ----------------------------------------------- model pickers
                Text(
                    text = stringResource(R.string.settings_models),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_models_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_model_tags),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_model_tags_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(Modifier.height(6.dp))
                ModelPicker(
                    settings = aiSettings,
                    selectedSlug = tagModelDraft,
                    onSelect = { tagModelDraft = it },
                    onRemoveCustom = viewModel::removeCustomModel
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.settings_model_reflection),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_model_reflection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(Modifier.height(6.dp))
                ModelPicker(
                    settings = aiSettings,
                    selectedSlug = reflectionModelDraft,
                    onSelect = { reflectionModelDraft = it },
                    onRemoveCustom = viewModel::removeCustomModel
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = customSlug,
                    onValueChange = { customSlug = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_model_custom)) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.settings_model_custom_hint),
                            color = TextTertiary
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        if (customSlug.isNotBlank()) {
                            IconButton(onClick = {
                                viewModel.addCustomModel(customSlug)
                                // Added to the list only — which of the two slots it should
                                // fill is a choice, and guessing it would silently repoint a
                                // job the user did not mean to change.
                                customSlug = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = stringResource(R.string.settings_model_add),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(Modifier.height(18.dp))

                // --------------------------------------------- cost routing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_low_priority),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = lowPriorityDraft,
                        onCheckedChange = { lowPriorityDraft = it },
                        colors = journalSwitchColors()
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_low_priority_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ save block
                if (dirty) {
                    Text(
                        text = stringResource(R.string.settings_unsaved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        viewModel.saveAiSettings(
                            apiKey = keyDraft,
                            tagModel = tagModelDraft,
                            reflectionModel = reflectionModelDraft,
                            lowPriority = lowPriorityDraft
                        )
                    },
                    enabled = dirty,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        disabledContentColor = TextTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_save_all),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (aiSettings.apiKey.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            viewModel.clearApiKey()
                            keyDraft = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.settings_api_key_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // --------------------------------------------------------------- zdr
        item {
            SectionCard(title = stringResource(R.string.settings_zdr_title)) {
                Text(
                    text = stringResource(R.string.settings_zdr_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // ------------------------------------------------------------ export
        item {
            SectionCard(title = stringResource(R.string.settings_export)) {
                Text(
                    text = stringResource(R.string.settings_export_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportJson.launch(viewModel.suggestedExportName(asJson = true)) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_export_json),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            exportMarkdown.launch(viewModel.suggestedExportName(asJson = false))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TextTertiary.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_export_md),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ import
        item {
            SectionCard(title = stringResource(R.string.settings_import)) {
                Text(
                    text = stringResource(R.string.settings_import_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { importBackup.launch(JournalImporter.OPEN_MIME_TYPES) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_json))
                }
            }
        }

        // -------------------------------------------------------------- data
        item {
            SectionCard(title = stringResource(R.string.settings_data)) {
                OutlinedButton(
                    onClick = { confirmRetag = true },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TextTertiary.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_retag))
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear))
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }

    if (confirmRetag) {
        AlertDialog(
            onDismissRequest = { confirmRetag = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.settings_retag)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_retag_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.retagAll()
                    confirmRetag = false
                }) {
                    Text(
                        text = stringResource(R.string.action_ok),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetag = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.settings_clear)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllEntries()
                    confirmClear = false
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }
}

/**
 * Dropdown over the curated models plus any the user added. Prices are shown inline because the
 * whole point of choosing is the cost tradeoff.
 */
@Composable
private fun ModelPicker(
    settings: AiSettings,
    selectedSlug: String,
    onSelect: (String) -> Unit,
    onRemoveCustom: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = settings.availableModels()
    val selected = options.firstOrNull { it.slug == selectedSlug }
    val customSlugs = settings.customModels.toSet()

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp)
                )
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected?.label ?: selectedSlug,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = selected?.let { "${it.slug} · ${it.price}" } ?: selectedSlug,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = stringResource(R.string.settings_model_choose),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        onSelect(option.slug)
                        expanded = false
                    },
                    text = {
                        Column {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option.slug == selectedSlug) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = "${option.price} / 1M",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                            option.note?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (option.slug in customSlugs) {
                            IconButton(onClick = {
                                onRemoveCustom(option.slug)
                                expanded = false
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(
                                        R.string.settings_model_remove
                                    ),
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun journalSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedTrackColor = CardSurface,
    uncheckedBorderColor = TextTertiary
)

@Composable
private fun AccentSwatch(
    option: AccentColor,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(option.color, CircleShape)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else option.color,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(option.labelRes),
                tint = OnAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
