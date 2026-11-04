package app.kassa.money.domain

import kotlin.math.roundToInt

/**
 * The rules of splitting an expense, the same on every platform: who shares it, who paid, equal or exact shares, what is refused,
 * and what the split means for your own spending and for what each person owes. The platform's repository does the writing (one
 * transaction); the entry page only holds what is picked and asks these for everything else. Plain Kotlin with JVM tests.
 *
 * Shares are cents (a `Long` of minor units), never `microShares` (those are for investment holdings). Equal shares come from
 * [Split.equalShares] (your share rounds to the cent, the odd cent goes to the first person); this file adds the state changes the
 * entry page makes, the checks, the plan that is stored, and how an edited entry reopens.
 *
 * What a saved split is, as Android's `EntryRepository.save` writes it: the expense keeps [Plan.myShare] (what spending counts)
 * and, when someone else paid, no account and that person as `personId`; one `loan_entries` line per person goes with it (what they
 * owe you, positive; or what you owe the payer, negative).
 */
object SplitRules {

    /** A split can name at most this many people. Bounds a crafted input; the people list of a phone is far smaller. */
    const val MAX_PEOPLE = 100

    /** "Split a transaction" on a person's page offers expenses from this many days back. */
    const val CANDIDATE_DAYS = 60

    /**
     * What the entry page has picked. [people] are the others sharing it, in the order picked. [exact] says the shares are
     * [exactShares] (person to cents) instead of equal. [paidBy] is the person who paid, null when you did (it is always one of
     * [people]).
     */
    data class Draft(
        val people: List<Long> = emptyList(),
        val exact: Boolean = false,
        val exactShares: Map<Long, Long> = emptyMap(),
        val paidBy: Long? = null,
    )

    /** What a saved split means: your share, who paid, and the lines of what each person owes (person to cents, never zero). */
    data class Plan(val shared: Boolean, val paidBy: Long?, val myShare: Long?, val owed: List<Pair<Long, Long>>)

    /** Only an expense is split, and only with someone. */
    fun isShared(kind: TxKind, d: Draft): Boolean = kind == TxKind.EXPENSE && d.people.isNotEmpty()

    /** Someone else paid the whole expense: it moves none of your accounts. */
    fun someoneElsePaid(kind: TxKind, d: Draft): Boolean = isShared(kind, d) && d.paidBy != null

    /** Each other person's share of [total]: equal, or what was typed (0 for a person none was typed for). Empty when not split. */
    fun shares(total: Long, kind: TxKind, d: Draft): Map<Long, Long> = when {
        !isShared(kind, d) -> emptyMap()
        d.exact -> d.people.associateWith { d.exactShares[it] ?: 0L }
        else -> Split.equalShares(total, d.people)
    }

    /** What is left for you; negative when the shares add up to more than [total]. */
    fun myShare(total: Long, kind: TxKind, d: Draft): Long = total - shares(total, kind, d).values.sum()

    // ---------- What the page does to the pick ----------

    /** Adds a person to the split, or takes them out (with their typed share, and as payer when they were it). */
    fun toggle(d: Draft, personId: Long): Draft = when {
        personId in d.people -> d.copy(
            people = d.people - personId,
            exactShares = d.exactShares - personId,
            paidBy = if (d.paidBy == personId) null else d.paidBy,
        )
        personId <= 0 || d.people.size >= MAX_PEOPLE -> d
        else -> d.copy(people = d.people + personId)
    }

    fun useEqual(d: Draft): Draft = d.copy(exact = false)

    /** Switches to exact amounts, starting from the equal split and keeping any share already typed. */
    fun useExact(total: Long, d: Draft): Draft =
        if (d.exact) d
        else d.copy(
            exact = true,
            exactShares = Split.equalShares(total, d.people) + d.exactShares.filterKeys { it in d.people && (d.exactShares[it] ?: 0L) > 0L },
        )

    /** Takes the share typed on the pad for [personId]; an empty or unreadable pad keeps their current share. */
    fun typedShare(d: Draft, personId: Long, typed: String): Draft {
        if (personId !in d.people || typed.isEmpty()) return d
        val cents = Money.parseCents(typed) ?: return d
        return if (cents in 0..InputRules.AMOUNT_MAX) d.copy(exactShares = d.exactShares + (personId to cents)) else d
    }

    /** Sets who paid: a person already in the split, or null for you. Anyone else is ignored. */
    fun withPayer(d: Draft, personId: Long?): Draft = if (personId == null || personId in d.people) d.copy(paidBy = personId) else d

    // ---------- Checks and the plan ----------

    /**
     * The first thing wrong with the split, or null when it can be saved (a split that isn't one is always fine). The sentence for
     * shares that add up to too much is Android's.
     */
    fun problem(total: Long, kind: TxKind, d: Draft): String? {
        if (!isShared(kind, d)) return null
        if (d.people.size > MAX_PEOPLE) return "Split with up to $MAX_PEOPLE people."
        if (d.people.toSet().size != d.people.size) return "Each person can only be in a split once."
        if (d.paidBy != null && d.paidBy !in d.people) return "Pick who paid."
        val parts = shares(total, kind, d)
        if (parts.values.any { it < 0 || it > InputRules.AMOUNT_MAX }) return "Each share must be between 0 and ${Money.format(InputRules.AMOUNT_MAX, wholeDollars = true)}."
        if (total - parts.values.sum() < 0) return "The shares add up to more than ${Money.format(total)}."
        return null
    }

    /**
     * What saving writes: your share, who paid and what each person owes ([Split.outcome]). Call after [problem] is null; with shares
     * that add up to too much it still answers (a negative share, nothing owed) rather than throwing.
     */
    fun plan(total: Long, kind: TxKind, d: Draft): Plan {
        if (!isShared(kind, d)) return Plan(false, null, null, emptyList())
        val parts = shares(total, kind, d)
        val mine = total - parts.values.sum()
        if (mine < 0) return Plan(true, d.paidBy, mine, emptyList())
        val outcome = Split.outcome(total, parts, d.paidBy)
        return Plan(true, d.paidBy, outcome.myShare, outcome.owed)
    }

    // ---------- Reopening an entry ----------

    /**
     * The pick for editing a stored entry, as Android's `entryStartFor`: when someone else paid (an expense with no account and a
     * person) the payer is the only person and their share is what is not yours; otherwise the people and shares come from the
     * entry's lines ([lines]: person to the sum of what they owe you, positive ones only count). [withPerson] (0 for none) adds
     * someone, as "Split a transaction" does. Exact is on when the shares are not what an equal split would give.
     */
    fun start(kind: TxKind, total: Long, accountId: Long?, personId: Long?, myShare: Long?, lines: Map<Long, Long>, withPerson: Long): Draft {
        val paidBy = if (kind == TxKind.EXPENSE && accountId == null) personId else null
        val shares: Map<Long, Long> =
            if (paidBy != null) mapOf(paidBy to (total - (myShare ?: total)).coerceAtLeast(0L))
            else lines.filterValues { it > 0L }
        val people = (shares.keys + listOfNotNull(withPerson.takeIf { it > 0 })).distinct()
        val exact = shares.isNotEmpty() && shares != Split.equalShares(total, people)
        return Draft(people = people, exact = exact, exactShares = shares, paidBy = paidBy)
    }

    // ---------- Showing it ----------

    /** A share as a whole percent of [total] (0 when there is no total). */
    fun percent(amount: Long, total: Long): Int = if (total <= 0) 0 else (amount * 100.0 / total).roundToInt()

    /**
     * Whether an entry is offered by "Split a transaction" on a person's page: an expense you paid from an account, not already split
     * with that person, not part of a loan payment, and no older than [CANDIDATE_DAYS] before [today] (epoch days).
     */
    fun canSplitWith(kind: TxKind, accountId: Long?, alreadySplitWithPerson: Boolean, isLoanEntry: Boolean, entryDay: Int, today: Int): Boolean =
        kind == TxKind.EXPENSE && accountId != null && !alreadySplitWithPerson && !isLoanEntry && entryDay >= today - CANDIDATE_DAYS

    /** The people the picker lists: A to Z, archived ones only while they are already in this split. */
    fun pickable(people: List<Person>, inSplit: List<Long>): List<Person> =
        people.filter { !it.archived || it.id in inSplit }.sortedWith(Names.order<Person> { it.name })
}
