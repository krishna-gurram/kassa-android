package app.kassa.money.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.cards.cardMoney
import app.kassa.money.ui.cards.cardTint
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.balanceColor
import app.kassa.money.ui.common.favourColor
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.format.DateTimeFormatter

/** At most this many tiles on Overview. */
const val MAX_PINS = 6

/** Something that can be pinned on Overview: one account, or everyone you owe or who owes you, as one tile. */
sealed interface Pin {
    val key: String
    val name: String

    data class Acct(val account: Account) : Pin {
        override val key get() = "acct:${account.id}"
        override val name get() = account.name
    }

    data object People : Pin {
        override val key = "people"
        override val name = "People"
    }
}

/** Every open account A to Z, then People when anyone is listed. */
fun pinChoices(ledger: Ledger): List<Pin> =
    ledger.accounts.accounts.filter { !it.archived }.sortedWith(Names.order { it.name }).map { Pin.Acct(it) } +
        listOfNotNull(Pin.People.takeIf { ledger.people.people.any { !it.archived } })

/** What Overview shows: the user's picks, or until they pick, the first six choices. */
fun pinned(ledger: Ledger, saved: Set<String>?): List<Pin> {
    val choices = pinChoices(ledger)
    return if (saved == null) choices.take(MAX_PINS) else choices.filter { it.key in saved }
}

/**
 * Overview's snapshot: a tile per pinned account with its balance, and for a card what's due. Tiles sit two
 * across; an odd last one takes the full row. With nothing pinned, one line offers to pin some.
 */
@Composable
fun AccountTiles(
    ledger: Ledger,
    onOpenAccount: (Long) -> Unit,
    onPeople: () -> Unit,
    onAll: () -> Unit,
    onPin: () -> Unit,
) {
    val saved by LocalSettings.current.pins.collectAsState()
    val pins = pinned(ledger, saved)
    val cards = ledger.cards().associateBy { it.account.id }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts", style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            LinkText("All accounts", onAll)
        }
        if (pins.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("No accounts pinned.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(end = 8.dp))
            LinkText("Pin accounts", onPin)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pins.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { pin ->
                        val m = Modifier.weight(1f)
                        when (pin) {
                            is Pin.Acct -> {
                                val a = pin.account
                                val balance = (ledger.accounts.balances[a.id] ?: 0) +
                                    if (a.type == AccountType.INVESTMENT) ledger.holdingsValue(a.id) else 0
                                val due = cards[a.id]?.snapshot?.takeIf { it.payable > 0 }?.let {
                                    val amount = Money.format(it.payable, wholeDollars = true)
                                    if (it.due.isBefore(ledger.today)) "$amount overdue" else "$amount due ${it.due.format(DateTimeFormatter.ofPattern("MMM d"))}"
                                }
                                // Cards and loans show what you owe, with no minus; a credit shows as a green plus.
                                val (text, color) = if (a.type.isDebt) cardMoney(-balance) to cardTint(-balance)
                                else Money.format(balance) to balanceColor(a.type, balance)
                                Tile(a.name, text, due, m, color) { onOpenAccount(a.id) }
                            }
                            Pin.People -> {
                                val owed = ledger.openPersonBalances().values
                                val theyOwe = owed.filter { it > 0 }.sum()
                                val youOwe = -owed.filter { it < 0 }.sum()
                                val net = if (theyOwe > 0 || youOwe == 0L) theyOwe else -youOwe
                                Tile(
                                    "People", (if (net > 0) "+" else if (net < 0) "−" else "") + Money.format(Math.abs(net)),
                                    if (theyOwe > 0 && youOwe > 0) "You owe ${Money.format(youOwe, wholeDollars = true)}" else null, m, favourColor(net), onPeople,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(name: String, amount: String, note: String?, modifier: Modifier, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(name, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(amount, style = KassaType.bodyStrong, color = color, maxLines = 1)
        // Every tile keeps the due line's space, so tiles are the same height whichever accounts are pinned.
        Text(note ?: "", style = KassaType.tiny, color = Kassa.colors.attention, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Settings › Home screen › Accounts on Overview: tick up to [MAX_PINS]. Until something changes here,
 * Overview shows the first six; the first change saves the whole set.
 */
@Composable
fun PinAccountsPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?) {
    val settings = LocalSettings.current
    val saved by settings.pins.collectAsState()
    val choices = pinChoices(ledger)
    val picked = pinned(ledger, saved).map { it.key }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Accounts on Overview", onBack)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("Pick up to $MAX_PINS. They show A to Z.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
            Text("${picked.size} of $MAX_PINS", style = KassaType.small, color = Kassa.colors.ink)
        }
        if (choices.isEmpty()) Text("Add an account in Accounts first.", style = KassaType.body, color = Kassa.colors.ink2)
        choices.forEach { pin ->
            val on = pin.key in picked
            val enabled = on || picked.size < MAX_PINS
            Column(Modifier.clickable(enabled = enabled) { settings.setPins(if (on) picked - pin.key else picked + pin.key) }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Kassa.colors.ink, checkmarkColor = Kassa.colors.onInk))
                    Text(pin.name, style = KassaType.body, color = if (enabled) Kassa.colors.ink else Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (pin == Pin.People) Text("one tile", style = KassaType.tiny, color = Kassa.colors.ink3)
                }
                HorizontalDivider(color = Kassa.colors.line)
            }
        }
        Gap(8)
        Text("With $MAX_PINS picked, untick one to pick another. Once you pick, new accounts stay off Overview until you pin them.",
            style = KassaType.tiny, color = Kassa.colors.ink3)
        Gap(24)
    }
}
