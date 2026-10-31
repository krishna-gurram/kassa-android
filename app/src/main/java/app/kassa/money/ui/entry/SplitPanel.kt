package app.kassa.money.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.ui.common.figureColor
import app.kassa.money.domain.Money
import app.kassa.money.domain.Person
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledSp

/**
 * Who shares an expense, who paid, and each share: equal, or exact amounts typed on the pad.
 * People are added in Accounts, not here, so the list only offers people who already exist.
 */
@Composable
internal fun ColumnScope.SplitPanel(s: EntryState, ledger: Ledger) {
    val people = ledger.people.people.associateBy { it.id }
    val typingFor = s.editingShare
    if (typingFor != null) ShareKeypad(s, people[typingFor])
    else SplitChoices(s, ledger, people)
}

/** The pad for one person's exact share. An empty pad keeps their current share. */
@Composable
private fun ColumnScope.ShareKeypad(s: EntryState, person: Person?) {
    PanelTitle("${person?.name ?: "Their"} share", back = { s.editingShare = null }, onDone = s::commitShare)
    val current = person?.let { s.exactShares[it.id] } ?: 0L
    Text(
        if (s.shareInput.isEmpty()) Money.format(current) else Money.symbol + displayAmount(s.shareInput),
        style = KassaType.hero.copy(fontSize = scaledSp(30f)), color = if (s.shareInput.isEmpty()) Kassa.colors.ink3 else Kassa.colors.ink,
        maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
    NumberPad(Modifier.weight(1f)) { k -> s.shareInput = applyKey(s.shareInput, k) }
    PrimaryButton("Done", s::commitShare)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SplitChoices(s: EntryState, ledger: Ledger, people: Map<Long, Person>) {
    PanelTitle("Split") { s.field = null }
    // Archived people stay listed only while they're already in this split.
    val everyone = ledger.people.people.filter { !it.archived || it.id in s.splitPeople }.sortedBy { it.name.lowercase() }
    val nameOf: (Long) -> String = { people[it]?.name ?: "?" }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SplitLabel("With")
        if (everyone.isEmpty()) Text("Add people in Accounts to split with them.", style = KassaType.small, color = Kassa.colors.ink2)
        FlowRow { everyone.forEach { p -> Chip(p.name, selected = p.id in s.splitPeople) { s.toggleSplitWith(p.id) } } }
        if (!s.shared) return@Column

        SplitLabel("Paid by")
        FlowRow {
            Chip("You", selected = s.paidBy == null) { s.paidBy = null }
            s.splitPeople.forEach { id -> Chip(nameOf(id), selected = s.paidBy == id) { s.paidBy = id } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SplitLabel("Shares", Modifier.weight(1f))
            Chip("Equally", selected = !s.exact, small = true, onClick = s::useEqualShares)
            Chip("Exact amounts", selected = s.exact, small = true, onClick = s::useExactShares)
        }
        val shares = s.shares
        ShareLine("You", s.myShare, s.cents, editable = false) {}
        s.splitPeople.forEach { id -> ShareLine(nameOf(id), shares[id] ?: 0L, s.cents, editable = s.exact) { s.startShare(id) } }
        if (s.myShare < 0) Text("The shares add up to more than ${Money.format(s.cents)}.", style = KassaType.small, color = Kassa.colors.attention)
        Text(
            s.paidBy?.let { "You owe ${nameOf(it)} ${Money.format(s.myShare.coerceAtLeast(0))}." } ?: "Each person owes you their share.",
            style = KassaType.small, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SplitLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, modifier = modifier.padding(top = 4.dp))
}

/** One person's share: name, percent of the total, amount. Exact shares are tapped to type an amount. */
@Composable
private fun ShareLine(name: String, amount: Long, total: Long, editable: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.ground)
            .then(if (editable) Modifier.border(1.dp, Kassa.colors.line, shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (total > 0 && !Money.isHidden) Text("${Math.round(amount * 100.0 / total)}%", style = KassaType.tiny, color = Kassa.colors.ink3, modifier = Modifier.padding(horizontal = 10.dp))
        Text(Money.format(amount), style = KassaType.bodyStrong, maxLines = 1, color = figureColor(if (amount < 0) Kassa.colors.attention else Kassa.colors.ink))
    }
}
