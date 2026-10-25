package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * Short messages ("Expense saved."), sized to their text. The caller places them clear of the Add button.
 * A sideways swipe dismisses one early.
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
            Text(
                data.visuals.message, style = KassaType.body, color = Kassa.colors.onInk, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.shadow(6.dp, shape).clip(shape).background(Kassa.colors.ink).padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}
