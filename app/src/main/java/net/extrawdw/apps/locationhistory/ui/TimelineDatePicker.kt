package net.extrawdw.apps.locationhistory.ui

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import net.extrawdw.apps.locationhistory.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Material represents a calendar date as UTC midnight, regardless of the device's timezone.
internal fun pickerMillis(dayEpoch: Long): Long =
    LocalDate.ofEpochDay(dayEpoch).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickerDay(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineDatePicker(
    selectedDay: Long,
    firstDay: Long,
    today: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val years = LocalDate.ofEpochDay(firstDay).year..LocalDate.ofEpochDay(today).year
    val selectableDates = remember(firstDay, today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = pickerDay(utcTimeMillis) in firstDay..today
            override fun isSelectableYear(year: Int) = year in years
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = pickerMillis(selectedDay),
        yearRange = years,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis?.let { pickerDay(it) in firstDay..today } == true,
                onClick = { state.selectedDateMillis?.let { onSelect(pickerDay(it)) } },
            ) { Text(stringResource(R.string.action_go_to_day)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}
