package app.kassa.money.ui.coach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.CoachSim
import app.kassa.money.domain.Money
import app.kassa.money.domain.SimEventKind
import app.kassa.money.domain.SimMovable
import app.kassa.money.domain.SimProblem
import app.kassa.money.domain.WhatIf
import app.kassa.money.domain.localDateOfEpochDay
import app.kassa.money.domain.toEpochDay
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.HeroLabel
import app.kassa.money.ui.common.HeroTone
import app.kassa.money.ui.common.IconBadge
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.TonalHero
import app.kassa.money.ui.common.heroInk
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.common.weekdayDate
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledDp
import app.kassa.money.ui.today.BigMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Coach simulator screens: "Can I afford it?" (the ask form, then its answer, in one page) and "Simulate my month" (the next 30 days day by
 * day, with what-if chips). What each shows is decided in SimModel (plain Kotlin, tested); this file words it from string resources and lays it
 * out. The rules run on the ledger's input off the main thread, only while a screen here is shown. Nothing here writes anything.
 */

private fun day(epochDay: Int) = localDateOfEpochDay(epochDay.toLong())

/** The form survives a fold or rotation: every field is a string or a number. */
private val SimFormSaver: Saver<SimForm, Any> = listSaver(
    save = { f -> listOf(f.amount, f.day.toString(), SimModel.whatIfText(f.extras), f.trying?.name.orEmpty(), f.tryItem, f.tryDay.toString(), f.tryAmount) },
    restore = { v ->
        if (v.size < 7) null
        else v[1].toIntOrNull()?.let { d ->
            SimForm(v[0], d, SimModel.whatIfs(v[2]), SimTry.values().firstOrNull { it.name == v[3] }, v[4], v[5].toIntOrNull() ?: d, v[6])
        }
    },
)

/** The payments a what-if can move, worked out off the main thread. */
@Composable
private fun rememberMovable(input: CoachInput?): List<SimMovable> {
    val movable by produceState(emptyList<SimMovable>(), input) {
        value = if (input == null) emptyList() else withContext(Dispatchers.Default) { CoachSim.movable(input) }
    }
    return movable
}

/** The simulation of [whatIfs], worked out off the main thread; null while it runs or before the ledger is there. */
@Composable
private fun rememberSim(input: CoachInput?, whatIfs: List<WhatIf>, cushion: Long, hidden: Boolean): SimView? {
    val view by produceState<SimView?>(null, input, whatIfs, cushion, hidden) {
        value = if (input == null) null else withContext(Dispatchers.Default) { SimModel.run(input, whatIfs, cushion, hidden) }
    }
    return view
}

// ---------- wording ----------

@Composable
private fun problemText(p: SimFormProblem): String = when (p) {
    SimFormProblem.AMOUNT_NEEDED -> stringResource(R.string.coach_sim_p_amount_needed)
    SimFormProblem.AMOUNT_BAD -> stringResource(R.string.coach_sim_p_amount_bad)
    SimFormProblem.AMOUNT_TOO_BIG -> stringResource(R.string.coach_sim_p_amount_big, Money.format(app.kassa.money.domain.InputRules.AMOUNT_MAX, wholeDollars = true))
    SimFormProblem.DAY_OUTSIDE -> stringResource(R.string.coach_sim_p_day)
    SimFormProblem.PICK_PAYMENT -> stringResource(R.string.coach_sim_p_pick)
    SimFormProblem.NO_LATER_DAY -> stringResource(R.string.coach_sim_p_later)
    SimFormProblem.TOO_MANY -> stringResource(R.string.coach_sim_p_many)
}

@Composable
private fun simProblemText(p: SimProblem?): String = stringResource(
    when (p) {
        SimProblem.TOO_MANY_WHAT_IFS -> R.string.coach_sim_prob_many
        SimProblem.BAD_AMOUNT -> R.string.coach_sim_prob_amount
        SimProblem.DAY_OUT_OF_RANGE -> R.string.coach_sim_prob_day
        SimProblem.UNKNOWN_ITEM -> R.string.coach_sim_prob_unknown
        SimProblem.NOT_LATER -> R.string.coach_sim_prob_later
        SimProblem.NO_PAYDAY -> R.string.coach_sim_prob_payday
        SimProblem.BAD_CUSHION -> R.string.coach_sim_prob_cushion
        SimProblem.BAD_ENCODING, null -> R.string.coach_sim_prob_text
    },
)

@Composable
private fun kindLabel(kind: SimEventKind): String = stringResource(
    when (kind) {
        SimEventKind.PAYCHECK -> R.string.coach_sim_ev_paycheck
        SimEventKind.BILL -> R.string.coach_sim_ev_bill
        SimEventKind.CARD_PAYMENT -> R.string.coach_sim_ev_card
        SimEventKind.LOAN_PAYMENT -> R.string.coach_sim_ev_loan
        SimEventKind.RECURRING -> R.string.coach_sim_ev_recurring
        SimEventKind.USUAL_SPENDING -> R.string.coach_sim_ev_recurring
        SimEventKind.SPEND -> R.string.coach_sim_ev_spend
        SimEventKind.EXTRA_MONTHLY -> R.string.coach_sim_ev_extra
    },
)

private fun kindIcon(kind: SimEventKind): ImageVector = when (kind) {
    SimEventKind.PAYCHECK -> Icons.Outlined.Payments
    SimEventKind.BILL -> Icons.Outlined.Receipt
    SimEventKind.CARD_PAYMENT -> Icons.Outlined.CreditCard
    SimEventKind.LOAN_PAYMENT -> Icons.Outlined.RequestQuote
    SimEventKind.RECURRING, SimEventKind.EXTRA_MONTHLY -> Icons.Outlined.Subscriptions
    SimEventKind.USUAL_SPENDING -> Icons.Outlined.Remove
    SimEventKind.SPEND -> Icons.Outlined.ShoppingCart
}

@Composable
private fun toneColor(t: SimTone): Color = when (t) {
    SimTone.GREEN -> Kassa.colors.positive
    SimTone.ORANGE -> Kassa.colors.attention
    SimTone.GREY -> Kassa.colors.ink
}

@Composable
private fun whatIfLabel(w: WhatIf, movable: List<SimMovable>): String = when (w) {
    is WhatIf.Spend -> stringResource(R.string.coach_sim_wi_spend, Money.format(w.amountCents), shortDate(day(w.day)))
    is WhatIf.DelayDue -> {
        val name = movable.firstOrNull { it.key == w.itemKey }?.name.orEmpty().ifBlank { stringResource(R.string.coach_sim_payment_fallback) }
        stringResource(R.string.coach_sim_wi_delay, name, shortDate(day(w.toDay)))
    }
    is WhatIf.MovePayday -> stringResource(R.string.coach_sim_wi_payday, shortDate(day(w.toDay)))
    is WhatIf.ExtraMonthly -> stringResource(R.string.coach_sim_wi_extra, Money.format(w.amountCents), shortDate(day(w.fromDay)))
}

// ---------- pieces ----------

/** A what-if as a chip with a cross; tapping it removes it. The whole chip is at least 48dp tall. */
@Composable
private fun WhatIfChip(label: String, onRemove: () -> Unit) {
    val remove = stringResource(R.string.coach_sim_remove, label)
    Row(
        Modifier.padding(end = 8.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.ink)
            .clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = remove }.padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KassaType.body, color = Kassa.colors.onInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Icon(Icons.Outlined.Close, null, tint = Kassa.colors.onInk, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

@Composable
private fun SimTag(text: String) {
    Text(
        text, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.chip).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** One thing that happens: kind icon, name, a line under it, the figure. Money in is green with a plus, a card payment due is orange, the rest ink with no sign. */
@Composable
private fun SimRowLine(kind: SimEventKind, name: String, amount: Long, estimated: Boolean, days: Int, date: String?) {
    val inflow = amount > 0L
    val tone = SimModel.figureTone(kind, inflow)
    val label = kindLabel(kind)
    val title = when {
        kind == SimEventKind.USUAL_SPENDING -> pluralStringResource(R.plurals.coach_sim_ev_usual, days, days)
        kind == SimEventKind.SPEND && name.isBlank() -> label
        name.isNotBlank() -> name
        else -> label
    }
    val sub = when {
        kind == SimEventKind.USUAL_SPENDING -> null
        date != null -> stringResource(R.string.coach_sim_ev_sub, label, date)
        else -> label
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(kindIcon(kind), 40)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = KassaType.body, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null || estimated) Row(verticalAlignment = Alignment.CenterVertically) {
                if (sub != null) Text(sub, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (estimated) SimTag(stringResource(R.string.coach_sim_estimated))
            }
        }
        Text(
            Money.format(kotlin.math.abs(amount), showPlus = inflow), style = KassaType.bodyStrong,
            color = app.kassa.money.ui.common.figureColor(toneColor(tone)), maxLines = 1,
        )
    }
    HorizontalDivider(color = Kassa.colors.line)
}

/** The hero of every answer that is not a month to draw, and the top of the ones that are. */
@Composable
private fun SimHero(view: SimView) {
    val tone = when (view.tone) { SimTone.GREEN -> HeroTone.GREEN; SimTone.ORANGE -> HeroTone.ORANGE; SimTone.GREY -> HeroTone.GREY }
    TonalHero(tone) {
        when (view.kind) {
            SimKind.HIDDEN -> {
                HeroLabel(stringResource(R.string.coach_sim_hidden_line))
                BigMoney(0L, sizeSp = 40f)
            }
            SimKind.NOT_ENOUGH_DATA -> {
                HeroLabel(stringResource(R.string.coach_sim_nodata_label))
                Text(stringResource(R.string.coach_sim_nodata_body), style = KassaType.body, color = heroInk(), modifier = Modifier.padding(vertical = 4.dp))
            }
            SimKind.NO_ACCOUNTS -> {
                HeroLabel(stringResource(R.string.coach_sim_noacc_label))
                Text(stringResource(R.string.coach_sim_noacc_body), style = KassaType.body, color = heroInk(), modifier = Modifier.padding(vertical = 4.dp))
            }
            SimKind.PROBLEM -> {
                HeroLabel(stringResource(R.string.coach_sim_problem_label))
                Text(simProblemText(view.problem), style = KassaType.body, color = heroInk(), modifier = Modifier.padding(vertical = 4.dp))
            }
            SimKind.ABOVE_CUSHION, SimKind.TIGHT, SimKind.SHORT -> {
                HeroLabel(
                    stringResource(
                        when {
                            view.kind == SimKind.TIGHT -> R.string.coach_sim_tight
                            view.kind == SimKind.SHORT -> R.string.coach_sim_short
                            view.cushion > 0L -> R.string.coach_sim_yes_cushion
                            else -> R.string.coach_sim_yes_zero
                        },
                    ),
                )
                if (view.kind == SimKind.SHORT) BigMoney(view.shortBy, sizeSp = 40f, color = Kassa.colors.attention) else BigMoney(view.lowest, sizeSp = 40f)
                Text(stringResource(R.string.coach_sim_lowest_note, weekdayDate(day(view.lowestDay))), style = KassaType.small, color = heroInk())
            }
        }
    }
}

/**
 * The month as a line: solid with the what-ifs, dashed without them, a dashed rule at the cushion (when there is one), a dot at the lowest point.
 * The figures are only used to place the lines; the words under it come from [SimView]. The chart gets its meaning for TalkBack from [description].
 */
@Composable
private fun SimChart(view: SimView, description: String) {
    val ink = Kassa.colors.ink
    val soft = Kassa.colors.ink3
    val line = Kassa.colors.line
    val dot = Kassa.colors.attention
    val balances = view.days.map { it.endBalanceCents }
    val lowestIndex = view.days.indexOfFirst { it.day == view.lowestDay }
    Canvas(Modifier.fillMaxWidth().height(scaledDp(130.dp)).semantics { contentDescription = description }) {
        val n = balances.size
        if (n < 2) return@Canvas
        val all = balances + view.baseline + listOf(view.cushion, 0L)
        val lo = all.min()
        val hi = all.max().let { if (it == lo) lo + 1 else it }
        val pad = 8.dp.toPx()
        val w = size.width
        val h = size.height
        fun x(i: Int) = i.toFloat() / (n - 1) * w
        fun y(v: Long) = h - pad - ((v - lo).toDouble() / (hi - lo).toDouble() * (h - 2 * pad)).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
        if (lo < 0L) drawLine(line, Offset(0f, y(0L)), Offset(w, y(0L)), strokeWidth = 1.dp.toPx())
        if (view.cushion > 0L) drawLine(soft, Offset(0f, y(view.cushion)), Offset(w, y(view.cushion)), strokeWidth = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
        for (i in 1 until n) drawLine(soft, Offset(x(i - 1), y(view.baseline[i - 1])), Offset(x(i), y(view.baseline[i])), strokeWidth = 2.dp.toPx(), pathEffect = dash)
        for (i in 1 until n) drawLine(ink, Offset(x(i - 1), y(balances[i - 1])), Offset(x(i), y(balances[i])), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
        if (lowestIndex >= 0) drawCircle(dot, 5.dp.toPx(), Offset(x(lowestIndex), y(balances[lowestIndex])))
    }
}

@Composable
private fun ChartBlock(view: SimView) {
    val lowestDate = weekdayDate(day(view.lowestDay))
    val description = if (view.cushion > 0L) stringResource(R.string.coach_sim_chart_cushion, Money.format(view.lowest), lowestDate, Money.format(view.cushion))
    else stringResource(R.string.coach_sim_chart, Money.format(view.lowest), lowestDate)
    Column(Modifier.padding(top = 14.dp)) {
        SimChart(view, description)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDate(day(view.startDay)), style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1)
            Text(shortDate(day(view.startDay + SimModel.HORIZON_DAYS)), style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1)
        }
        Text(
            if (view.cushion > 0L) stringResource(R.string.coach_sim_legend_cushion, Money.format(view.cushion)) else stringResource(R.string.coach_sim_legend),
            style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------- Can I afford it? ----------

/** The ask form, then the answer. "Check my month" shows the answer; "Try another date" comes back to the form with everything kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanIAffordPage(
    l: Ledger,
    input: CoachInput?,
    cushion: Long,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEveryDay: (whatIfs: String) -> Unit,
) {
    val today = l.today.toEpochDay().toInt()
    val last = SimModel.lastDay(today)
    var form by rememberSaveable(stateSaver = SimFormSaver) { mutableStateOf(SimModel.newForm(today)) }
    var answered by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<SimFormProblem?>(null) }
    val hidden = Money.isHidden
    val movable = rememberMovable(input)
    BackHandler(enabled = answered) { answered = false }
    val back: (() -> Unit)? = if (answered) ({ answered = false }) else onBack

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(stringResource(if (answered) R.string.coach_sim_answer_title else R.string.coach_sim_entry_title), back)
        if (!answered) {
            val amountProblem = problem?.takeIf { it == SimFormProblem.AMOUNT_NEEDED || it == SimFormProblem.AMOUNT_BAD || it == SimFormProblem.AMOUNT_TOO_BIG }
            AmountField(
                value = form.amount, onChange = { form = form.copy(amount = it); problem = null },
                label = stringResource(R.string.coach_sim_amount), modifier = Modifier.fillMaxWidth(),
                problem = amountProblem?.let { problemText(it) },
            )
            Text(stringResource(R.string.coach_sim_on).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            DateRow(day(form.day), l.today, allowFuture = true, min = l.today, max = day(last)) { form = form.copy(day = it.toEpochDay().toInt()); problem = null }
            SectionHeader(stringResource(R.string.coach_sim_or_try))
            Gap(8)
            FlowRow {
                listOf(
                    SimTry.SKIP_BILL to R.string.coach_sim_chip_skip, SimTry.DELAY_PAYMENT to R.string.coach_sim_chip_delay,
                    SimTry.MOVE_PAYDAY to R.string.coach_sim_chip_payday, SimTry.EXTRA_MONTHLY to R.string.coach_sim_chip_extra,
                ).forEach { (t, label) ->
                    Chip(stringResource(label), selected = form.trying == t) { form = SimModel.startTry(form, t, movable, today); problem = null }
                }
            }
            form.trying?.let { t ->
                TryPanel(t, form, movable, l, today, problem, onForm = { form = it; problem = null }, onAdd = {
                    when (val made = SimModel.makeTry(form, movable, today)) {
                        is SimModel.Made.Ok -> { form = made.form; problem = null }
                        is SimModel.Made.No -> problem = made.problem
                    }
                })
            }
            if (form.extras.isNotEmpty()) {
                Gap(8)
                FlowRow {
                    form.extras.forEachIndexed { i, w -> WhatIfChip(whatIfLabel(w, movable)) { form = form.copy(extras = SimModel.remove(form.extras, i)); problem = null } }
                }
            }
            val general = problem?.takeIf { it == SimFormProblem.DAY_OUTSIDE || it == SimFormProblem.TOO_MANY }
            if (general != null) Text(problemText(general), style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 8.dp))
            Gap(20)
            PrimaryButton(stringResource(R.string.coach_sim_check)) {
                val p = SimModel.problem(form, today)
                if (p == null) { problem = null; answered = true } else problem = p
            }
            Gap(24)
        } else {
            val whatIfs = form.whatIfs()
            val view = rememberSim(input, whatIfs, cushion, hidden)
            if (view != null) AnswerBody(view, onEveryDay = { onEveryDay(form.text()) })
            Gap(16)
            SecondaryButton(stringResource(R.string.coach_sim_another), { answered = false })
            Gap(24)
        }
    }
}

/** The little panel under the chips for the chip that is open: what to pick, then "Add to the check". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TryPanel(
    t: SimTry,
    form: SimForm,
    movable: List<SimMovable>,
    l: Ledger,
    today: Int,
    problem: SimFormProblem?,
    onForm: (SimForm) -> Unit,
    onAdd: () -> Unit,
) {
    val last = SimModel.lastDay(today)
    Column(Modifier.padding(top = 8.dp)) {
        when (t) {
            SimTry.SKIP_BILL, SimTry.DELAY_PAYMENT -> {
                val items = SimModel.movableFor(t, movable, today)
                if (items.isEmpty()) {
                    Text(stringResource(if (t == SimTry.SKIP_BILL) R.string.coach_sim_none_bills else R.string.coach_sim_none_payments), style = KassaType.body, color = Kassa.colors.ink2)
                    return@Column
                }
                Text(stringResource(R.string.coach_sim_which), style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 4.dp))
                FlowRow {
                    items.forEach { m ->
                        val name = m.name.ifBlank { stringResource(R.string.coach_sim_payment_fallback) }
                        Chip(stringResource(R.string.coach_sim_item, name, shortDate(day(m.day))), selected = m.key == form.tryItem, small = true) {
                            onForm(form.copy(tryItem = m.key, tryDay = last))
                        }
                    }
                }
                val picked = items.firstOrNull { it.key == form.tryItem }
                if (picked != null) {
                    Text(stringResource(R.string.coach_sim_move_to).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    DateRow(day(form.tryDay), l.today, allowFuture = true, min = day(picked.day + 1), max = day(last)) { onForm(form.copy(tryDay = it.toEpochDay().toInt())) }
                }
            }
            SimTry.MOVE_PAYDAY -> {
                Text(stringResource(R.string.coach_sim_payday_label).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 6.dp))
                DateRow(day(form.tryDay), l.today, allowFuture = true, min = l.today, max = day(last)) { onForm(form.copy(tryDay = it.toEpochDay().toInt())) }
            }
            SimTry.EXTRA_MONTHLY -> {
                AmountField(
                    value = form.tryAmount, onChange = { onForm(form.copy(tryAmount = it)) },
                    label = stringResource(R.string.coach_sim_extra_amount), modifier = Modifier.fillMaxWidth(),
                    problem = problem?.takeIf { it == SimFormProblem.AMOUNT_NEEDED || it == SimFormProblem.AMOUNT_BAD || it == SimFormProblem.AMOUNT_TOO_BIG }?.let { problemText(it) },
                )
                Text(stringResource(R.string.coach_sim_extra_from).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                DateRow(day(form.tryDay), l.today, allowFuture = true, min = l.today, max = day(last)) { onForm(form.copy(tryDay = it.toEpochDay().toInt())) }
            }
        }
        if (problem == SimFormProblem.PICK_PAYMENT || problem == SimFormProblem.NO_LATER_DAY) {
            Text(problemText(problem), style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 8.dp))
        }
        Gap(12)
        SecondaryButton(stringResource(R.string.coach_sim_add_try), onAdd)
    }
}

/** The answer: the verdict hero, the month as a line, then what moves. */
@Composable
private fun AnswerBody(view: SimView, onEveryDay: () -> Unit) {
    SimHero(view)
    if (view.hasChart) {
        ChartBlock(view)
        val moves = SimModel.whatMoves(view.days)
        if (moves.isNotEmpty()) {
            SectionHeader(stringResource(R.string.coach_sim_moves), stringResource(R.string.coach_sim_every_day), onClick = onEveryDay)
            moves.forEach { (d, e) ->
                SimRowLine(e.kind, e.name, e.amountCents, e.estimated, 1, shortDate(day(d)))
            }
        } else {
            SectionHeader(stringResource(R.string.coach_sim_moves), stringResource(R.string.coach_sim_every_day), onClick = onEveryDay)
        }
    }
}

// ---------- Simulate my month ----------

/** The next 30 days day by day. [initial] is the what-if text it opens with; the chips remove what-ifs, "As planned" removes them all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimulateMonthPage(
    input: CoachInput?,
    cushion: Long,
    initial: String,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val whatIfs = remember(text) { SimModel.whatIfs(text) }
    val hidden = Money.isHidden
    val view = rememberSim(input, whatIfs, cushion, hidden)
    val movable = rememberMovable(input)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(stringResource(R.string.coach_sim_month_title), onBack)
        FlowRow {
            Chip(stringResource(R.string.coach_sim_as_planned), selected = whatIfs.isEmpty()) { text = "" }
            whatIfs.forEachIndexed { i, w -> WhatIfChip(whatIfLabel(w, movable)) { text = SimModel.removeFromText(text, i) } }
        }
        if (view != null) {
            if (!view.hasChart) {
                Gap(8)
                SimHero(view)
            } else {
                val groups = remember(view) { SimModel.groups(view.days, view.lowestDay) }
                groups.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(weekdayDate(day(g.day)).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(
                            if (g.lowest) stringResource(R.string.coach_sim_lowest_chip, Money.format(g.balance)) else Money.format(g.balance),
                            style = KassaType.small, color = if (g.lowest) Kassa.colors.attention else Kassa.colors.ink2, maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(Kassa.colors.chip).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    HorizontalDivider(color = Kassa.colors.line)
                    g.rows.forEach { r -> SimRowLine(r.kind, r.name, r.amount, r.estimated, r.days, null) }
                }
                Text(stringResource(R.string.coach_sim_fine), style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Gap(24)
    }
}
