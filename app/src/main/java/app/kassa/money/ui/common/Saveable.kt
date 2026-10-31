package app.kassa.money.ui.common

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.LocalDate

/** A date kept across a fold or rotation as its epoch day, which is how Kassa stores days everywhere. */
val LocalDateSaver: Saver<LocalDate, Long> = Saver(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })

/**
 * State for a date field that survives the screen being recreated. Use it like `var date by rememberSavedDate { today }`;
 * [initial] runs only when there is nothing saved yet.
 */
@Composable
fun rememberSavedDate(initial: () -> LocalDate): MutableState<LocalDate> =
    rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(initial()) }
