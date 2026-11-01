package app.kassa.money.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountArrangement
import app.kassa.money.domain.AccountSection
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.ui.common.shortDate
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

/** At most this many tiles on Overview. */
const val MAX_PINS = 8

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
        // An identifier only; screens show stringResource(R.string.today_people) for it.
        override val name = "People"
    }
}

/** Every open account in the user's Accounts order (A to Z until they arrange), then People when anyone is listed. */
fun pinChoices(ledger: Ledger, arrangement: AccountArrangement? = null): List<Pin> {
    val open = ledger.accounts.accounts.filter { it.isActive }
    val ordered = if (arrangement == null) open.sortedWith(Names.order { it.name })
    else arrangement.flatten(open, { AccountSection.of(it.type).name }, { AccountSection.of(it.type).title }, { it.id }, { it.name })
    return ordered.map { Pin.Acct(it) } + listOfNotNull(Pin.People.takeIf { ledger.people.people.any { !it.archived } })
}

/** What Overview shows: the user's picks, or until they pick, the first eight choices. */
fun pinned(ledger: Ledger, saved: Set<String>?, arrangement: AccountArrangement? = null): List<Pin> {
    val choices = pinChoices(ledger, arrangement)
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
    unfolded: Boolean = false,
) {
    val saved by LocalSettings.current.pins.collectAsState()
    val arrangement by LocalSettings.current.accountArrangement.collectAsState()
    val pins = pinned(ledger, saved, arrangement)
    val cards = ledger.cards().associateBy { it.account.id }
    Column {
        // Unfolded, the header sits flush with the top of the hero beside it, and the cells are a little taller.
        app.kassa.money.ui.common.SectionHeader(stringResource(R.string.today_accounts_header), stringResource(R.string.today_accounts_all), onClick = onAll, top = if (unfolded) 0.dp else 20.dp)
        val tileMin = if (unfolded) 80.dp else 56.dp
        if (pins.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_accounts_none), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(end = 8.dp))
            LinkText(stringResource(R.string.today_accounts_pin), onPin)
        }
        Column {
            pins.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    pair.forEach { pin ->
                        val m = Modifier.weight(1f)
                        when (pin) {
                            is Pin.Acct -> {
                                val a = pin.account
                                val balance = (ledger.accounts.balances[a.id] ?: 0) +
                                    if (a.type == AccountType.INVESTMENT) ledger.holdingsValue(a.id) else 0
                                val due = cards[a.id]?.snapshot?.takeIf { it.payable > 0 }?.let {
                                    val amount = Money.format(it.payable, wholeDollars = true)
                                    if (it.due.isBefore(ledger.today)) stringResource(R.string.today_tile_overdue, amount) else stringResource(R.string.today_tile_due, amount, shortDate(it.due))
                                }
                                // Cards and loans show what you owe, with no minus; a credit shows as a green plus.
                                val (text, color) = if (a.type.isDebt) cardMoney(-balance) to cardTint(-balance)
                                else Money.format(balance) to balanceColor(a.type, balance)
                                Tile(a.name, text, due, m, color, tileMin) { onOpenAccount(a.id) }
                            }
                            Pin.People -> {
                                val owed = ledger.openPersonBalances().values
                                val theyOwe = owed.filter { it > 0 }.sum()
                                val youOwe = -owed.filter { it < 0 }.sum()
                                val net = if (theyOwe > 0 || youOwe == 0L) theyOwe else -youOwe
                                Tile(
                                    stringResource(R.string.today_people), (if (Money.isHidden) "" else if (net > 0) "+" else if (net < 0) "−" else "") + Money.format(Math.abs(net)),
                                    if (theyOwe > 0 && youOwe > 0) stringResource(R.string.today_tile_you_owe, Money.format(youOwe, wholeDollars = true)) else null, m, favourColor(net), tileMin, onPeople,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One pinned account as a hairline cell: its name over its balance, a due line when there is one, and a chevron. */
@Composable
private fun Tile(name: String, amount: String, note: String?, modifier: Modifier, color: androidx.compose.ui.graphics.Color, minHeight: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        HorizontalDivider(color = Kassa.colors.line)
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(amount, style = KassaType.bodyStrong, color = color, maxLines = 1)
                // Every tile keeps the due line's space, so tiles are the same height whichever accounts are pinned.
                Text(note ?: "", style = KassaType.tiny, color = app.kassa.money.ui.common.figureColor(Kassa.colors.attention), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Icon(app.kassa.money.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Settings › Home screen › Accounts on Overview: tick up to [MAX_PINS]. Until something changes here,
 * Overview shows the first eight; the first change saves the whole set.
 */
@Composable
fun PinAccountsPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?) {
    val settings = LocalSettings.current
    val saved by settings.pins.collectAsState()
    val arrangement by settings.accountArrangement.collectAsState()
    val choices = pinChoices(ledger, arrangement)
    val picked = pinned(ledger, saved, arrangement).map { it.key }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(stringResource(R.string.today_pins_title), onBack, amounts = false)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(stringResource(R.string.today_pins_hint, MAX_PINS), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.today_pins_count, picked.size, MAX_PINS), style = KassaType.small, color = Kassa.colors.ink)
        }
        if (choices.isEmpty()) Text(stringResource(R.string.today_pins_empty), style = KassaType.body, color = Kassa.colors.ink2)
        choices.forEach { pin ->
            val on = pin.key in picked
            val enabled = on || picked.size < MAX_PINS
            Column(Modifier.clickable(enabled = enabled) { settings.setPins(if (on) picked - pin.key else picked + pin.key) }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Kassa.colors.ink, checkmarkColor = Kassa.colors.onInk))
                    Text(if (pin == Pin.People) stringResource(R.string.today_people) else pin.name, style = KassaType.body, color = if (enabled) Kassa.colors.ink else Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (pin == Pin.People) Text(stringResource(R.string.today_pins_one_tile), style = KassaType.tiny, color = Kassa.colors.ink3)
                }
                HorizontalDivider(color = Kassa.colors.line)
            }
        }
        Gap(8)
        Text(stringResource(R.string.today_pins_footer, MAX_PINS),
            style = KassaType.tiny, color = Kassa.colors.ink3)
        Gap(24)
    }
}
