package com.journal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journal.app.R
import com.journal.app.ui.theme.TextSecondary
import com.journal.app.ui.theme.TextTertiary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GEORGIAN = Locale("ka", "GE")
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", GEORGIAN)

/**
 * Weekday initials, Monday first — Georgia's week starts on Monday, and `DayOfWeek.MONDAY` is
 * already ordinal 1, so no locale-dependent first-day lookup is needed.
 */
private val WEEKDAY_LABELS = listOf("ორ", "სა", "ოთ", "ხუ", "პა", "შა", "კვ")

/**
 * A month grid where each day is shaded by how much was written on it.
 *
 * ### Why a heatmap rather than dots
 * Dots answer "did I write?" but stop at three or four before they become a smear. The thing
 * worth seeing when scrolling back through a month is *intensity* — the week that went quiet,
 * the day everything happened — so the count drives a background alpha and is also printed. The
 * shading carries the pattern at a glance; the number is there when the exact figure matters.
 *
 * Intensity is bucketed, not continuous: a linear ramp against the month's own maximum would
 * make a quiet month look identical to a busy one, since both would top out fully saturated.
 */
@Composable
fun JournalCalendar(
    dayCounts: Map<LocalDate, Int>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now()
) {
    // Opening on the selected day's month (not always the current one) keeps the calendar and
    // the feed showing the same period when it is re-opened with a filter already active.
    // Held as the ISO string so it survives process death without a custom Saver.
    var monthKey by rememberSaveable {
        mutableStateOf(YearMonth.from(selectedDate ?: today).toString())
    }
    val month = remember(monthKey) { YearMonth.parse(monthKey) }

    Column(modifier = modifier.fillMaxWidth()) {

        // ------------------------------------------------------------- header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { monthKey = month.minusMonths(1).toString() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = stringResource(R.string.calendar_previous_month),
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = month.atDay(1).format(MONTH_TITLE),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { monthKey = month.plusMonths(1).toString() },
                // Nothing has been written in the future, so forward past this month is a grid
                // of empty cells with nothing to select.
                enabled = month < YearMonth.from(today),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = stringResource(R.string.calendar_next_month),
                    tint = if (month < YearMonth.from(today)) TextSecondary else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ------------------------------------------------------- weekday row
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // --------------------------------------------------------- day grid
        val weeks = remember(month) { weeksOf(month) }
        weeks.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayCell(
                            date = date,
                            count = dayCounts[date] ?: 0,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    count: Int,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val fill = when {
        isSelected -> accent
        count <= 0 -> Color.Transparent
        else -> accent.copy(alpha = intensityFor(count))
    }
    // On a saturated accent fill the dark on-accent colour is the legible one; on a faint tint
    // it would disappear, so the text follows the background rather than the other way round.
    val label = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        count > 0 -> MaterialTheme.colorScheme.onSurface
        else -> TextTertiary
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(fill, CircleShape)
            .border(
                width = if (isToday && !isSelected) 1.dp else 0.dp,
                color = if (isToday && !isSelected) accent else Color.Transparent,
                shape = CircleShape
            )
            // Empty days stay tappable: selecting one is a legitimate way to confirm that
            // nothing was written, and a dead cell just reads as a broken calendar.
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = label
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    } else {
                        accent
                    }
                )
            }
        }
    }
}

/** Four buckets: wrote something, a couple, a handful, a lot. */
private fun intensityFor(count: Int): Float = when {
    count <= 1 -> 0.16f
    count <= 3 -> 0.30f
    count <= 6 -> 0.46f
    else -> 0.62f
}

/**
 * The month laid out as Monday-first weeks, padded with nulls so every row holds seven cells.
 *
 * Nulls rather than the neighbouring months' days: a greyed-out 31st of the previous month is
 * one mis-tap away from filtering the feed to a day the user was not looking at.
 */
internal fun weeksOf(month: YearMonth): List<List<LocalDate?>> {
    val first = month.atDay(1)
    // DayOfWeek.MONDAY.value == 1, so this is 0 for a month starting on Monday.
    val leadingBlanks = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = buildList<LocalDate?> {
        repeat(leadingBlanks) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        while (size % 7 != 0) add(null)
    }
    return cells.chunked(7)
}
