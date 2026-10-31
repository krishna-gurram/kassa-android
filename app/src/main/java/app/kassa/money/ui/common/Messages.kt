package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lets a screen show a message with one action ("Back to A to Z", Undo) that outlives the screen: it runs on the app's
 * scope, so leaving the tab doesn't take the message, or its action, away.
 */
class Messages(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun withAction(message: String, action: String, onAction: () -> Unit) {
        scope.launch {
            if (host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** The app's [Messages]; AppRoot provides it to the tabs. Null where there is nothing to show them on. */
val LocalMessages = staticCompositionLocalOf<Messages?> { null }

/**
 * Short messages ("Expense saved."), sized to their text, with an action beside the text when it has one. The caller
 * places them clear of the Add button. A sideways swipe dismisses one early.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) data.dismiss()
            true
        })
        SwipeToDismissBox(swipe, backgroundContent = {}) {
            val shape = RoundedCornerShape(12.dp)
            val action = data.visuals.actionLabel
            Row(
                Modifier.shadow(6.dp, shape).clip(shape).background(Kassa.colors.ink),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    data.visuals.message, style = KassaType.body, color = Kassa.colors.onInk, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 14.dp, end = if (action == null) 14.dp else 4.dp, top = 11.dp, bottom = 11.dp),
                )
                if (action != null) TextButton(onClick = { data.performAction() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(action, style = KassaType.bodyStrong, color = Kassa.colors.onInk)
                }
            }
        }
    }
}
