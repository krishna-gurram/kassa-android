package app.kassa.money.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.CoachReport
import app.kassa.money.domain.EmergencyFund
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberText
import app.kassa.money.domain.SafeToSpend
import app.kassa.money.domain.WeeklyCheckIn
import app.kassa.money.domain.localDateOfEpochDay
import app.kassa.money.domain.toEpochDay
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.HeroLabel
import app.kassa.money.ui.common.HeroTone
import app.kassa.money.ui.common.IconBadge
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.Panel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.TonalHero
import app.kassa.money.ui.common.heroInk
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.today.BigMoney
import java.text.NumberFormat
import java.util.Locale

/*
 * The Coach screens: the Overview card, the Coach page (safe to spend and this week's cards) and the weekly check-in page. What each shows is
 * decided in CoachModel (plain Kotlin, tested); this file words it from string resources and lays it out. Nothing here writes anything: a
 * decision only opens the existing budget editor, and Not now only remembers the week.
 */

private fun percentText(p: Int): String = NumberFormat.getPercentInstance(Locale.getDefault()).format(p / 100.0)

private fun categoryName(l: Ledger, id: Long): String = l.categoryById[id]?.name ?: ""

/** The one line the Overview card and the check-in row show under "Your week in 3 points". */
@Composable
private fun teaserLine(t: Teaser, l: Ledger): String = when (t.kind) {
    TeaserKind.NEUTRAL -> stringResource(R.string.coach_teaser_hidden)
    TeaserKind.WATCH -> t.watch?.let { watchTitle(it, l) } ?: ""
    TeaserKind.WIN -> t.win?.let { winTitle(it, l) } ?: ""
}

@Composable
private fun paceTitle(kind: PaceKind, name: String): String = stringResource(
    when (kind) {
        PaceKind.OVER_BUDGET -> R.string.coach_pace_over_title
        PaceKind.PACE_OVER_BUDGET -> R.string.coach_pace_budget_title
        PaceKind.ABOVE_LAST -> R.string.coach_pace_last_title
    },
    name,
)

@Composable
private fun watchTitle(w: WeeklyCheckIn.Watch, l: Ledger): String = when (w.kind) {
    WeeklyCheckIn.WatchKind.CATEGORY_PACE -> paceTitle(CoachModel.paceKind(w), categoryName(l, w.categoryId))
    WeeklyCheckIn.WatchKind.SUBSCRIPTION_RISE -> stringResource(R.string.coach_subs_title, w.name, Money.format(w.overBy))
}

@Composable
private fun winTitle(w: WeeklyCheckIn.Win, l: Ledger): String = stringResource(
    if (w.kind == WeeklyCheckIn.WinKind.UNDER_BUDGET) R.string.coach_win_budget_title else R.string.coach_win_usual_title,
    categoryName(l, w.categoryId), Money.format(w.savedBy),
)

/** A card with an icon, a title, a line under it and a chevron: the Overview card, the check-in row and the insight cards share it. */
@Composable
private fun CoachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Kassa.colors.raised).border(1.dp, Kassa.colors.line, RoundedCornerShape(20.dp))
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, 40)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (body.isNotEmpty()) Text(body, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Overview's "Your week in 3 points" card under the spending hero; it opens Coach. Callers show it only when [CoachModel.teaser] gave one. */
@Composable
fun CoachTeaserCard(teaser: Teaser, l: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CoachRow(Icons.Outlined.Lightbulb, stringResource(R.string.coach_teaser_title), teaserLine(teaser, l), onClick, modifier)
}

/** The Coach page: safe to spend, then this week's cards for the rows that exist. */
@Composable
fun CoachPage(
    l: Ledger,
    report: CoachReport?,
    showCards: Boolean,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onCheckIn: () -> Unit,
    onTarget: (Target) -> Unit,
) {
    val hidden = Money.isHidden
    val view = remember(report, hidden, showCards) { report?.let { CoachModel.view(it, hidden, showCards) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(stringResource(R.string.coach_title), onBack)
        if (view == null) return@Column
        SafeHero(view)
        SectionHeader(stringResource(R.string.coach_this_week))
        val teaser = CoachModel.teaser(view, enabled = true, dismissedWeek = app.kassa.money.data.Settings.NO_WEEK)
        val checkInBody = when {
            view.checkIn.state == CheckInState.NOT_ENOUGH_DATA -> stringResource(R.string.coach_checkin_not_ready)
            teaser != null -> teaserLine(teaser, l)
            else -> stringResource(R.string.coach_checkin_h_quiet)
        }
        CoachRow(Icons.Outlined.Lightbulb, stringResource(R.string.coach_teaser_title), checkInBody, onCheckIn)
        Gap(10)
        when {
            hidden -> Text(stringResource(R.string.coach_hidden_note), style = KassaType.body, color = Kassa.colors.ink2)
            !showCards -> Text(stringResource(R.string.coach_cards_off), style = KassaType.body, color = Kassa.colors.ink2)
            view.cards.isEmpty() -> Text(stringResource(R.string.coach_no_cards), style = KassaType.body, color = Kassa.colors.ink2)
            else -> view.cards.forEach { card ->
                val target = CoachModel.target(card)
                InsightRow(card, l, onClick = target?.let { t -> { onTarget(t) } })
                Gap(10)
            }
        }
        Gap(24)
    }
}

@Composable
private fun InsightRow(card: InsightCard, l: Ledger, onClick: (() -> Unit)?) {
    when (card) {
        is InsightCard.Pace -> {
            val r = card.row
            val body = when (card.kind) {
                PaceKind.OVER_BUDGET -> stringResource(R.string.coach_pace_over_body, Money.format(r.spent), Money.format(r.budget ?: 0L))
                PaceKind.PACE_OVER_BUDGET -> stringResource(R.string.coach_pace_budget_body, Money.format(r.spent), Money.format(r.projection), Money.format(r.budget ?: 0L))
                PaceKind.ABOVE_LAST -> stringResource(R.string.coach_pace_last_body, Money.format(r.spent), Money.format(r.lastTotal))
            }
            CoachRow(Icons.Outlined.Insights, paceTitle(card.kind, categoryName(l, r.categoryId)), body, onClick)
        }
        is InsightCard.Subscription -> CoachRow(
            Icons.Outlined.Subscriptions,
            stringResource(R.string.coach_subs_title, card.row.name, Money.format(card.row.change)),
            stringResource(R.string.coach_subs_body, Money.format(card.monthlyTotal)),
            onClick,
        )
        is InsightCard.Fund -> {
            val f = card.fund
            val mark = NumberText.decimalSeparator().toString()
            val months = CoachModel.tenthsText(f.tenths, mark)
            val body = if (f.targetTenths > 0 && f.weeklyToTarget > 0L) {
                pluralStringResource(R.plurals.coach_fund_body_target, EmergencyFund.WEEKS, EmergencyFund.WEEKS, Money.format(f.weeklyToTarget), CoachModel.tenthsText(f.targetTenths, mark))
            } else {
                stringResource(R.string.coach_fund_body_done, months, Money.format(f.monthlySpending))
            }
            CoachRow(Icons.Outlined.Shield, stringResource(R.string.coach_fund_title, months), body, onClick)
        }
    }
}

/** The safe-to-spend hero: green while there is something safe, orange for a shortfall, grey with no account (and while balances are hidden, decided inside [TonalHero]). */
@Composable
private fun SafeHero(view: CoachView) {
    val s = view.safe
    val res = LocalContext.current.resources
    val until = shortDate(localDateOfEpochDay(s.untilDay.toLong()))
    TonalHero(when (view.safeKind) { SafeKind.SPENDABLE -> HeroTone.GREEN; SafeKind.SHORTFALL -> HeroTone.ORANGE; else -> HeroTone.GREY }) {
        when (view.safeKind) {
            SafeKind.NO_ACCOUNTS -> {
                HeroLabel(stringResource(R.string.coach_safe_none_label))
                Text(stringResource(R.string.coach_safe_none_body), style = KassaType.body, color = heroInk(), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
            SafeKind.HIDDEN -> {
                HeroLabel(stringResource(R.string.coach_safe_none_label))
                BigMoney(0L, sizeSp = 40f)
                Text(stringResource(R.string.coach_safe_hidden_note), style = KassaType.small, color = heroInk())
            }
            SafeKind.SHORTFALL -> {
                HeroLabel(stringResource(R.string.coach_safe_short_label, until))
                BigMoney(s.shortBy, sizeSp = 40f, color = Kassa.colors.attention)
                Text(stringResource(R.string.coach_safe_short_note), style = KassaType.small, color = heroInk(), modifier = Modifier.padding(top = 4.dp))
            }
            SafeKind.SPENDABLE -> {
                HeroLabel(stringResource(R.string.coach_safe_label, until))
                BigMoney(s.amount, sizeSp = 40f)
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(heroInk().copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(CoachModel.usedFraction(s.usedPercent)).fillMaxHeight().background(heroInk()))
                }
                Text(stringResource(R.string.coach_safe_used, percentText(s.usedPercent)), style = KassaType.small, color = heroInk(), modifier = Modifier.padding(top = 4.dp))
                val names = s.parts.map { res.getString(partName(it.kind)) }
                if (names.isNotEmpty()) {
                    val line = CoachModel.joinList(
                        names,
                        join = { a, b -> res.getString(R.string.coach_list_join, a, b) },
                        last = { a, b -> res.getString(R.string.coach_list_last, a, b) },
                    )
                    Text(stringResource(R.string.coach_safe_after, line), style = KassaType.small, color = heroInk())
                }
                when (s.basis) {
                    SafeToSpend.Basis.NONE -> Text(stringResource(R.string.coach_safe_basis_none), style = KassaType.small, color = heroInk())
                    SafeToSpend.Basis.THIS_MONTH -> Text(stringResource(R.string.coach_safe_basis_month), style = KassaType.small, color = heroInk())
                    SafeToSpend.Basis.HISTORY -> {}
                }
            }
        }
    }
}

private fun partName(kind: SafeToSpend.PartKind): Int = when (kind) {
    SafeToSpend.PartKind.BILLS -> R.string.coach_part_bills
    SafeToSpend.PartKind.RECURRING -> R.string.coach_part_recurring
    SafeToSpend.PartKind.CARDS -> R.string.coach_part_cards
    SafeToSpend.PartKind.LOANS -> R.string.coach_part_loans
    SafeToSpend.PartKind.USUAL_SPENDING -> R.string.coach_part_usual
}

/** The weekly check-in page: one win, one watch-out, one decision. "Set the budget" opens the existing budget editor with the suggested monthly amount; it saves nothing itself. */
@Composable
fun CheckInPage(
    l: Ledger,
    report: CoachReport?,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onSetBudget: (categoryId: Long, monthlyCents: Long) -> Unit,
    onNotNow: (weekStartDay: Int) -> Unit,
) {
    val hidden = Money.isHidden
    val view = remember(report, hidden) { report?.let { CoachModel.view(it, hidden) } }
    val c = view?.checkIn
    val title = stringResource(R.string.coach_checkin_title, shortDate(localDateOfEpochDay((c?.weekStartDay ?: l.weekStart.toEpochDay().toInt()).toLong())))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(title, onBack)
        if (c == null) return@Column
        when (c.state) {
            CheckInState.NOT_ENOUGH_DATA -> {
                Text(stringResource(R.string.coach_checkin_wait_title), style = KassaType.title, color = Kassa.colors.ink)
                Text(stringResource(R.string.coach_checkin_wait_body), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp))
            }
            CheckInState.HIDDEN -> Text(stringResource(R.string.coach_hidden_note), style = KassaType.body, color = Kassa.colors.ink2)
            CheckInState.READY -> {
                Text(
                    stringResource(
                        when (c.headline) {
                            Headline.WIN_AND_WATCH -> R.string.coach_checkin_h_both
                            Headline.WIN -> R.string.coach_checkin_h_win
                            Headline.WATCH -> R.string.coach_checkin_h_watch
                            Headline.QUIET -> R.string.coach_checkin_h_quiet
                        },
                    ),
                    style = KassaType.title, color = Kassa.colors.ink,
                )
                val spent = Money.format(c.spent)
                val subtitle = when (CoachModel.versus(c)) {
                    CoachModel.Versus.NONE -> stringResource(R.string.coach_checkin_spent, spent)
                    CoachModel.Versus.SAME -> stringResource(R.string.coach_checkin_same, spent)
                    CoachModel.Versus.UNDER -> stringResource(R.string.coach_checkin_under, spent, Money.format(CoachModel.versusAmount(c)))
                    CoachModel.Versus.OVER -> stringResource(R.string.coach_checkin_over, spent, Money.format(CoachModel.versusAmount(c)))
                }
                Text(subtitle, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp))
                c.win?.let { w ->
                    SectionHeader(stringResource(R.string.coach_one_win))
                    Text(winTitle(w, l), style = KassaType.section, color = Kassa.colors.ink)
                    val body = if (w.kind == WeeklyCheckIn.WinKind.UNDER_BUDGET) R.string.coach_win_budget_body else R.string.coach_win_usual_body
                    Text(stringResource(body, Money.format(w.spent), Money.format(w.reference)), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
                }
                c.watch?.let { w -> WatchBlock(w, l) }
                c.decision?.let { d ->
                    SectionHeader(stringResource(R.string.coach_one_decision))
                    Text(stringResource(R.string.coach_decision_title, categoryName(l, d.categoryId)), style = KassaType.section, color = Kassa.colors.ink)
                    Text(
                        pluralStringResource(R.plurals.coach_decision_body, d.monthsUsed, Money.format(d.monthlyBudget), d.monthsUsed, categoryName(l, d.categoryId)),
                        style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
                    )
                    Gap(12)
                    PrimaryButton(stringResource(R.string.coach_set_budget)) { onSetBudget(d.categoryId, d.monthlyBudget) }
                    Gap(8)
                    SecondaryButton(stringResource(R.string.coach_not_now), { onNotNow(c.weekStartDay) })
                }
            }
        }
        Gap(24)
    }
}

@Composable
private fun WatchBlock(w: WeeklyCheckIn.Watch, l: Ledger) {
    SectionHeader(stringResource(R.string.coach_one_watch))
    Text(watchTitle(w, l), style = KassaType.section, color = Kassa.colors.ink)
    when (w.kind) {
        WeeklyCheckIn.WatchKind.CATEGORY_PACE -> {
            val body = when (CoachModel.paceKind(w)) {
                PaceKind.OVER_BUDGET -> stringResource(R.string.coach_pace_over_body, Money.format(w.amount), Money.format(w.reference))
                PaceKind.PACE_OVER_BUDGET -> stringResource(R.string.coach_pace_budget_body, Money.format(w.amount), Money.format(w.projection), Money.format(w.reference))
                PaceKind.ABOVE_LAST -> stringResource(R.string.coach_pace_last_body, Money.format(w.amount), Money.format(w.reference))
            }
            Text(body, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
            if (w.referenceKind == WeeklyCheckIn.Reference.BUDGET && w.usedPercent >= 0) {
                Text(
                    stringResource(R.string.coach_watch_budget_pill, Money.format(w.reference), percentText(w.usedPercent), w.dayOfPeriod),
                    style = KassaType.small, color = Kassa.colors.attention,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.attention.copy(alpha = 0.14f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        WeeklyCheckIn.WatchKind.SUBSCRIPTION_RISE -> Text(
            stringResource(R.string.coach_watch_sub_body, Money.format(w.reference), Money.format(w.amount)),
            style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
