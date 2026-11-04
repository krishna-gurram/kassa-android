package app.kassa.money.shared

import app.kassa.money.domain.Money
import app.kassa.money.domain.SplitRules
import app.kassa.money.domain.TxKind

/*
 * Splitting an expense on the iOS entry page. The rules are :domain's `SplitRules` (equal-share rounding, exact shares that must not
 * add up to more than the amount, who paid, what each person owes); this file turns them into flat values for Swift: a [SplitState]
 * the page holds and hands back to each call, plain Strings, Longs and small classes, never a Kotlin enum or a nullable Long (0 means
 * "none": ids start at 1). Amount text is written in the ledger's currency and never masked (typed amounts are never masked).
 */

/** People ids as "1,2,3" and shares as "1:500,2:300": the flat text a split is kept in. Reading is forgiving, what is not a number is dropped. */
internal object SplitText {
    fun ids(text: String): List<Long> = text.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }

    fun pairs(text: String): Map<Long, Long> = buildMap {
        for (part in text.split(",")) {
            val bits = part.split(":")
            if (bits.size != 2) continue
            val id = bits[0].trim().toLongOrNull() ?: continue
            val cents = bits[1].trim().toLongOrNull() ?: continue
            if (id > 0) put(id, cents)
        }
    }

    fun ids(list: List<Long>): String = list.joinToString(",")

    fun pairs(map: Map<Long, Long>): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }
}

/**
 * What the entry page has picked for a split: [people] (ids as "1,2"), whether the shares are [exact] (then [shares] holds each typed
 * share as "id:cents") and who [paidBy] (0 when you did). Immutable: every change returns a new one. [encoded] is one text a scene can
 * keep, and [SplitBridge.decode] reads it back.
 */
class SplitState(val people: String, val exact: Boolean, val shares: String, val paidBy: Long) {

    internal fun draft(): SplitRules.Draft = SplitRules.Draft(
        people = SplitText.ids(people).distinct(),
        exact = exact,
        exactShares = SplitText.pairs(shares),
        paidBy = if (paidBy > 0) paidBy else null,
    )

    /** The state as one text: people, exact, shares, payer. */
    val encoded: String get() = listOf(people, if (exact) "1" else "0", shares, paidBy.toString()).joinToString("|")

    internal companion object {
        fun of(d: SplitRules.Draft): SplitState = SplitState(SplitText.ids(d.people), d.exact, SplitText.pairs(d.exactShares), d.paidBy ?: 0L)
    }
}

/** One line of what saving writes: [personId] owes you [amount] (negative: you owe them). */
data class SplitOwedData(val personId: Long, val amount: Long)

/** A person as the split picker lists them. */
data class SplitPersonData(val id: Long, val name: String, val archived: Boolean)

/**
 * One row of the shares list: [personId] is 0 for you. [amountText] is the share, [percentText] a whole percent ("33%", empty without a
 * total), [negative] is true for your own share when the others' add up to more than the amount.
 */
data class SplitLineData(val personId: Long, val amountCents: Long, val amountText: String, val percentText: String, val negative: Boolean)

/**
 * The split section as the page shows it. [shared]: the expense is split with someone. [myShareText]: "Your share" figure.
 * [problem]: the refusal sentence ("The shares add up to more than $120.00."), empty when fine. [payerOwedText]: what you owe the payer
 * when someone else paid. [someoneElsePaid]: [SplitState.paidBy] is set on a split.
 */
data class SplitViewData(
    val shared: Boolean,
    val someoneElsePaid: Boolean,
    val lines: List<SplitLineData>,
    val myShareCents: Long,
    val myShareText: String,
    val myShareNegative: Boolean,
    val payerOwedText: String,
    val problem: String,
)

/** What the split panel and the Split row of the entry page ask of the rules. */
object SplitBridge {

    /** No split. */
    fun none(): SplitState = SplitState("", false, "", 0L)

    /** [text] (a [SplitState.encoded]) as a state; anything unreadable is no split. */
    fun decode(text: String): SplitState {
        val parts = text.split("|")
        if (parts.size != 4) return none()
        val draft = SplitState(parts[0], parts[1] == "1", parts[2], parts[3].toLongOrNull() ?: 0L).draft()
        val kept = draft.copy(
            people = draft.people.take(SplitRules.MAX_PEOPLE),
            exactShares = draft.exactShares.filterValues { it in 0..app.kassa.money.domain.InputRules.AMOUNT_MAX },
        )
        return SplitState.of(SplitRules.withPayer(kept.copy(paidBy = null), kept.paidBy))
    }

    fun isShared(state: SplitState, kind: String): Boolean {
        val k = txKind(kind) ?: return false
        return SplitRules.isShared(k, state.draft())
    }

    fun someoneElsePaid(state: SplitState, kind: String): Boolean {
        val k = txKind(kind) ?: return false
        return SplitRules.someoneElsePaid(k, state.draft())
    }

    /** Whether [personId] is in the split. */
    fun isSelected(state: SplitState, personId: Long): Boolean = personId in state.draft().people

    /** Adds the person, or takes them out. */
    fun toggle(state: SplitState, personId: Long): SplitState = SplitState.of(SplitRules.toggle(state.draft(), personId))

    fun useEqual(state: SplitState): SplitState = SplitState.of(SplitRules.useEqual(state.draft()))

    /** Exact amounts, starting from the equal split of [total] and keeping what was typed. */
    fun useExact(state: SplitState, total: Long): SplitState = SplitState.of(SplitRules.useExact(total, state.draft()))

    /** Takes the share typed on the pad for [personId] (an empty pad keeps their share). */
    fun typedShare(state: SplitState, personId: Long, typed: String): SplitState =
        SplitState.of(SplitRules.typedShare(state.draft(), personId, typed))

    /** Who paid: a person in the split, or 0 for you. */
    fun payer(state: SplitState, personId: Long): SplitState =
        SplitState.of(SplitRules.withPayer(state.draft(), if (personId > 0) personId else null))

    /** The rows, your share and the problem for an expense of [total] cents, in [currency]. */
    fun view(state: SplitState, kind: String, total: Long, currency: String): SplitViewData = withMoney(currency) {
        val k = txKind(kind) ?: TxKind.EXPENSE
        val d = state.draft()
        val shared = SplitRules.isShared(k, d)
        if (!shared) {
            return@withMoney SplitViewData(false, false, emptyList(), total, Money.format(total), false, "", "")
        }
        val shares = SplitRules.shares(total, k, d)
        val mine = total - shares.values.sum()
        fun line(id: Long, amount: Long) =
            SplitLineData(id, amount, Money.format(amount), if (total > 0) "${SplitRules.percent(amount, total)}%" else "", amount < 0)
        val lines = listOf(line(0, mine)) + d.people.map { line(it, shares[it] ?: 0L) }
        SplitViewData(
            shared = true,
            someoneElsePaid = d.paidBy != null,
            lines = lines,
            myShareCents = mine,
            myShareText = Money.format(mine),
            myShareNegative = mine < 0,
            payerOwedText = Money.format(mine.coerceAtLeast(0)),
            problem = SplitRules.problem(total, k, d) ?: "",
        )
    }

    /** The first problem of the split, empty text when it can be saved. */
    fun problem(state: SplitState, kind: String, total: Long, currency: String): String = withMoney(currency) {
        val k = txKind(kind) ?: return@withMoney ""
        SplitRules.problem(total, k, state.draft()) ?: ""
    }

    // What an entry carries into the store (see `EntryInput`): only an expense is split.

    internal fun peopleFor(state: SplitState, kind: String): String {
        val k = txKind(kind) ?: return ""
        val d = state.draft()
        return if (SplitRules.isShared(k, d)) SplitText.ids(d.people) else ""
    }

    internal fun sharesFor(state: SplitState, kind: String, total: Long): String {
        val k = txKind(kind) ?: return ""
        val d = state.draft()
        return if (SplitRules.isShared(k, d)) SplitText.pairs(SplitRules.shares(total, k, d)) else ""
    }

    internal fun payerFor(state: SplitState, kind: String): Long {
        val k = txKind(kind) ?: return 0L
        val d = state.draft()
        return if (SplitRules.isShared(k, d)) d.paidBy ?: 0L else 0L
    }
}
