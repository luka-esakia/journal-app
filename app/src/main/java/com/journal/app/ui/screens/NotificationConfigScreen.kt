package com.journal.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journal.app.R
import com.journal.app.data.local.NotificationConfig
import com.journal.app.notification.NotificationHelper
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.components.SectionCard
import com.journal.app.ui.components.TagChip
import com.journal.app.ui.components.formatEntryTime
import com.journal.app.ui.theme.CardSurface
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Configures the spaced-random engine: on/off, how many prompts a day, and the daily window.
 * The preview block makes the chunking visible, which is the whole point of the algorithm.
 */
@Composable
fun NotificationConfigScreen(
    viewModel: JournalViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val saved by viewModel.config.collectAsStateWithLifecycle()

    // Local editable copy; resets whenever the persisted config changes.
    var draft by remember(saved) { mutableStateOf(saved) }
    var editing by remember { mutableStateOf<TimeField?>(null) }

    var notificationsAllowed by remember { mutableStateOf(NotificationHelper.areNotificationsEnabled(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(NotificationHelper.canScheduleExactAlarms(context)) }

    // The user can flip either permission in system settings and come straight back.
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationHelper.areNotificationsEnabled(context)
        exactAlarmsAllowed = NotificationHelper.canScheduleExactAlarms(context)
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsAllowed = granted }

    val zone = remember { ZoneId.systemDefault() }
    val dayStart = remember(draft) {
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    // Seeded from the config so the preview stays stable while the user reads it.
    val preview = remember(draft, dayStart) {
        viewModel.previewSlots(draft, dayStart, draft.hashCode())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.config_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (!notificationsAllowed) {
            item {
                WarningCard(
                    text = stringResource(R.string.config_permission_needed),
                    actionLabel = stringResource(R.string.config_grant_permission),
                    onAction = {
                        val needsRuntimeGrant =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED

                        if (needsRuntimeGrant) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Permission held but the app/channel is muted: open system settings.
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
        }

        if (!exactAlarmsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                WarningCard(
                    text = stringResource(R.string.config_exact_alarm),
                    actionLabel = stringResource(R.string.config_open_settings),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = if (draft.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            TextTertiary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.config_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = CardSurface,
                            uncheckedBorderColor = TextTertiary
                        )
                    )
                }
                if (!draft.enabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.config_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.config_count, draft.dailyCount)) {
                Slider(
                    value = draft.dailyCount.toFloat(),
                    onValueChange = { draft = draft.copy(dailyCount = it.roundToInt()) },
                    valueRange = NotificationConfig.MIN_DAILY_COUNT.toFloat()..
                        NotificationConfig.MAX_DAILY_COUNT.toFloat(),
                    steps = NotificationConfig.MAX_DAILY_COUNT - NotificationConfig.MIN_DAILY_COUNT - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = TextTertiary.copy(alpha = 0.4f)
                    )
                )
                Text(
                    text = stringResource(R.string.config_chunk, draft.chunkMinutes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.config_window)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TimeButton(
                        label = stringResource(R.string.config_start_label),
                        value = formatMinuteOfDay(draft.startMinuteOfDay),
                        onClick = { editing = TimeField.START },
                        modifier = Modifier.weight(1f)
                    )
                    TimeButton(
                        label = stringResource(R.string.config_end_label),
                        value = formatMinuteOfDay(draft.endMinuteOfDay),
                        onClick = { editing = TimeField.END },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${stringResource(R.string.config_chunk, draft.chunkMinutes())} · " +
                        "min ${NotificationHelper.MIN_GAP_MINUTES}′",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.config_preview)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    preview.forEachIndexed { index, slot ->
                        Row(verticalAlignment = Alignment.Top) {
                            TagChip(tag = formatEntryTime(slot.triggerAtMillis))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = slot.prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (index == 0) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    TextSecondary
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.saveConfig(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.config_save),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    editing?.let { field ->
        val initial = when (field) {
            TimeField.START -> draft.startMinuteOfDay
            TimeField.END -> draft.endMinuteOfDay
        }
        TimePickerDialog(
            initialMinuteOfDay = initial,
            onDismiss = { editing = null },
            onConfirm = { minuteOfDay ->
                draft = when (field) {
                    TimeField.START -> draft.copy(startMinuteOfDay = minuteOfDay)
                    TimeField.END -> draft.copy(endMinuteOfDay = minuteOfDay)
                }
                editing = null
            }
        )
    }
}

private enum class TimeField { START, END }

/**
 * Label above, time below.
 *
 * The previous single-line version ("დაწყება: 10:00") wrapped to two lines inside a half-width
 * column and was then clipped by a fixed 48dp height. Stacking the two pieces keeps each on one
 * line, and sizing by content — `defaultMinSize` rather than `height` — means Georgian descenders
 * can never be cut off, whatever the font scale.
 */
@Composable
private fun TimeButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    containerColor = CardSurface,
                    selectorColor = MaterialTheme.colorScheme.primary,
                    timeSelectorSelectedContainerColor =
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(
                    text = stringResource(R.string.action_ok),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        }
    )
}

@Composable
private fun WarningCard(
    text: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    SectionCard(contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 0..1439 → "09:30". */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60) % 24
    val minute = minuteOfDay % 60
    return "%02d:%02d".format(hour, minute)
}
