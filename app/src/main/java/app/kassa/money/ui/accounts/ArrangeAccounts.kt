package app.kassa.money.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.AccountGroupView
import app.kassa.money.ui.common.AutoScroll
import app.kassa.money.ui.common.ReorderHandle
import app.kassa.money.ui.common.rememberReorderState
import app.kassa.money.ui.common.reorderItem
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * One column of groups while arranging: each is a single row (chevron, name and count, a drag handle) that opens to
 * its accounts, each a name and a handle, with no balances. Groups drag among [groups] only, accounts only inside their
 * own group. [onGroupsMoved] gets the column's group keys in their new order, [onAccountsMoved] a group's account ids;
 * the handles' Move up and Move down actions for TalkBack use [moveGroup] and [moveAccount], the one-step moves.
 */
@Composable
internal fun ArrangeGroups(
    groups: List<AccountGroupView>,
    opened: List<String>,
    onToggle: (String) -> Unit,
    auto: AutoScroll,
    onGroupsMoved: (within: List<String>, reordered: List<String>) -> Unit,
    onAccountsMoved: (group: String, reordered: List<Long>) -> Unit,
    moveGroup: (group: String, within: List<String>, delta: Int) -> Unit,
    moveAccount: (group: String, id: Long, delta: Int) -> Unit,
) {
    val within = groups.map { it.section.name }
    val state = rememberReorderState<String>(10.dp, auto)
    state.keys = within
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEachIndexed { at, group ->
            val groupKey = group.section.name
            key(groupKey) {
                GroupCard(
                    stringResource(R.string.accounts_group_title_count, group.section.title, group.lines.size), null, Kassa.colors.ink,
                    open = groupKey in opened, onToggle = { onToggle(groupKey) },
                    modifier = Modifier.reorderItem(state, groupKey, RoundedCornerShape(16.dp)),
                    trailing = {
                        ReorderHandle(
                            state, groupKey, group.section.title,
                            canUp = at > 0, canDown = at < within.lastIndex,
                            onUp = { moveGroup(groupKey, within, -1) }, onDown = { moveGroup(groupKey, within, 1) },
                            onMoved = { onGroupsMoved(within, it) },
                        )
                    },
                ) {
                    ArrangeRows(group, auto, onAccountsMoved, moveAccount)
                }
            }
        }
    }
}

/** The accounts of an open group, in a column of their own that drags apart from the groups'. */
@Composable
private fun ArrangeRows(
    group: AccountGroupView,
    auto: AutoScroll,
    onMoved: (String, List<Long>) -> Unit,
    moveAccount: (String, Long, Int) -> Unit,
) {
    val groupKey = group.section.name
    val ids = group.lines.map { it.account.id }
    val state = rememberReorderState<Long>(0.dp, auto)
    state.keys = ids
    Column {
        group.lines.forEachIndexed { i, line ->
            val id = line.account.id
            key(id) {
                // Opaque, so a row held over the others hides them rather than showing through.
                Column(Modifier.reorderItem(state, id).background(Kassa.colors.raised)) {
                    HorizontalDivider(color = Kassa.colors.line)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line.account.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        ReorderHandle(
                            state, id, line.account.name,
                            canUp = i > 0, canDown = i < ids.lastIndex,
                            onUp = { moveAccount(groupKey, id, -1) }, onDown = { moveAccount(groupKey, id, 1) },
                            onMoved = { onMoved(groupKey, it) },
                        )
                    }
                }
            }
        }
    }
}
