package app.kassa.money.importer

import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Budget
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryColors
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.Money
import app.kassa.money.domain.Person
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.balanceOf
import app.kassa.money.importer.MoneyManagerImporter.Companion.fit
import app.kassa.money.importer.MoneyManagerImporter.Companion.uniqueNames

/** What the user decides before a Cashew import: each account's kind, accounts to leave out, and what each loan is. */
data class CashewChoices(
    val accountTypes: Map<String, AccountType> = emptyMap(),
    val leaveOut: Set<String> = emptySet(),
    val loans: Map<String, LoanChoice> = emptyMap(),
)

enum class LoanChoice { PERSON, ACCOUNT, ENTRIES }

/** A quick look at a Cashew backup, enough to ask the questions before planning the import. */
data class CashewScan(
    val accounts: List<Wallet>,
    val loans: List<Loan>,
    val categories: Int,
    val subcategories: Int,
    val entries: Int,
    val transfers: Int,
    val firstMillis: Long?,
    val lastMillis: Long?,
    /** The backup's main currency (upper case), when it says. */
    val mainCurrency: String?,
) {
    data class Wallet(
        val pk: String, val name: String,
        /** Upper case, or null for the main currency. */
        val currency: String?,
        val balance: Long, val entries: Int,
        /** Transfers with other accounts, which become plain entries if this account is left out. */
        val transfers: Int,
    )

    data class Loan(val pk: String, val name: String, val lent: Boolean, val amount: Long, val payments: Int)

    /** Accounts in a currency other than [phoneCurrency]. */
    fun otherCurrency(phoneCurrency: String): List<Wallet> =
        accounts.filter { (it.currency ?: mainCurrency ?: phoneCurrency).uppercase() != phoneCurrency.uppercase() }
}

/**
 * Turns a Cashew backup into an [ImportPlan]. Rules, in the order they apply to each paid entry:
 * - category "0" is Cashew's Balance Correction: a pair across two accounts is a transfer, a lone one is folded
 *   into its account's starting balance;
 * - an entry tied to a loan follows that loan's [LoanChoice];
 * - anything else is an expense (money out) or income (money in), in its subcategory when it has one.
 * Upcoming entries that aren't paid stay behind; monthly subscriptions among them become bills. Category limits
 * from monthly budgets become category budgets. Amounts come in unchanged, whatever the account's currency.
 */
class CashewImporter(private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {

    fun scan(source: CashewSource): CashewScan {
        val wallets = source.wallets().sortedBy { it.order }
        val cats = source.categories().filter { it.pk != BALANCE_CORRECTION }
        val rows = source.transactions()
        val paid = rows.filter { it.recorded }
        val transferPks = transferPairs(paid).flatMap { listOf(it.first.pk, it.second.pk) }.toSet()
        val byWallet = paid.groupBy { it.walletPk }
        val main = source.primaryWalletPk()?.let { pk -> wallets.firstOrNull { it.pk == pk } }?.currency
            ?: wallets.mapNotNull { it.currency }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return CashewScan(
            accounts = wallets.map { w ->
                val own = byWallet[w.pk].orEmpty()
                CashewScan.Wallet(
                    pk = w.pk, name = fit(w.name, InputRules.NAME_MAX), currency = w.currency?.uppercase(),
                    balance = own.sumOf { cents(it.amount) ?: 0L }, entries = own.size,
                    transfers = own.count { it.pk in transferPks },
                )
            },
            loans = source.objectives().filter { it.type == LOAN }.map { o ->
                val linked = paid.filter { it.objectiveLoanPk == o.pk }
                val principal = linked.filter { it.isPrincipalOf(o) }.sumOf { -(cents(it.amount) ?: 0L) }.let { kotlin.math.abs(it) }
                CashewScan.Loan(
                    pk = o.pk, name = fit(o.name, InputRules.NAME_MAX), lent = o.income,
                    amount = if (principal != 0L) principal else kotlin.math.abs(cents(o.amount) ?: 0L),
                    payments = linked.count { !it.isPrincipalOf(o) },
                )
            },
            categories = cats.count { it.mainCategoryPk == null },
            subcategories = cats.count { it.mainCategoryPk != null },
            entries = paid.count { it.pk !in transferPks },
            transfers = transferPks.size / 2,
            firstMillis = paid.minOfOrNull { it.epochMillis },
            lastMillis = paid.maxOfOrNull { it.epochMillis },
            mainCurrency = main?.uppercase(),
        )
    }

    fun plan(source: CashewSource, choices: CashewChoices): ImportPlan {
        val issues = mutableListOf<ImportIssue>()

        // Accounts: the ones kept, in Cashew's order, then one account for each loan the user made an account.
        val wallets = source.wallets().sortedBy { it.order }.filter { it.pk !in choices.leaveOut }
        val objectives = source.objectives()
        val loans = objectives.filter { it.type == LOAN }.associateBy { it.pk }
        val loanAccounts = loans.values.filter { choices.loans[it.pk] == LoanChoice.ACCOUNT }
        val names = uniqueNames(wallets.map { fit(it.name, InputRules.NAME_MAX - 3) } + loanAccounts.map { fit(it.name, InputRules.NAME_MAX - 3) })
        val accountIds = HashMap<String, Long>()
        val accounts = mutableListOf<Account>()
        wallets.forEachIndexed { i, w ->
            val id = (i + 1).toLong()
            accountIds[w.pk] = id
            accounts += Account(id = id, name = names[i], type = choices.accountTypes[w.pk] ?: AccountType.OTHER_ASSET, sortOrder = i, externalId = "cashew:${w.pk}")
        }
        val loanAccountIds = HashMap<String, Long>()
        loanAccounts.forEachIndexed { j, o ->
            val i = wallets.size + j
            val id = (i + 1).toLong()
            loanAccountIds[o.pk] = id
            accounts += Account(id = id, name = names[i], type = if (o.income) AccountType.OTHER_ASSET else AccountType.LOAN, sortOrder = i, externalId = "cashew-loan:${o.pk}")
        }
        val opening = HashMap<Long, Long>()

        // Categories: main ones first so subcategories can point at them. Balance Correction isn't a category in Kassa.
        val cwCats = source.categories().filter { it.pk != BALANCE_CORRECTION }
        val catIds = HashMap<String, Long>()
        val categories = mutableListOf<Category>()
        val merged = mutableListOf<String>()
        var nextCat = 1L
        fun sameName(kind: CategoryKind, parentId: Long?, name: String) =
            categories.firstOrNull { it.kind == kind && it.parentId == parentId && it.name.equals(name, ignoreCase = true) }
        val mains = cwCats.filter { it.mainCategoryPk == null || cwCats.none { c -> c.pk == it.mainCategoryPk } }
            .sortedWith(compareBy({ it.income }, { it.order }))
        for (c in mains) {
            val kind = if (c.income) CategoryKind.INCOME else CategoryKind.EXPENSE
            val name = fit(c.name, InputRules.CATEGORY_NAME_MAX).ifBlank { "Category" }
            val same = sameName(kind, null, name)
            if (same != null) { catIds[c.pk] = same.id; merged += same.name; continue }
            val id = nextCat++
            catIds[c.pk] = id
            categories += Category(
                id = id, name = name, kind = kind, icon = iconFor(c),
                color = colorOf(c.colour) ?: CategoryColors.next(categories, kind),
                sortOrder = categories.size, externalId = "cashew:${c.pk}",
            )
        }
        for (c in cwCats.filter { it !in mains }.sortedBy { it.order }) {
            val parentId = catIds[c.mainCategoryPk] ?: continue
            val parent = categories.first { it.id == parentId }
            val name = fit(c.name, InputRules.CATEGORY_NAME_MAX).ifBlank { "Subcategory" }
            val same = sameName(parent.kind, parentId, name)
            if (same != null) { catIds[c.pk] = same.id; merged += same.name; continue }
            val id = nextCat++
            catIds[c.pk] = id
            val icon = iconFor(c)
            categories += Category(
                id = id, name = name, kind = parent.kind, parentId = parentId,
                icon = if (icon == CategoryIcon.DEFAULT) parent.icon else icon, color = parent.color,
                countsTowardDaily = parent.countsTowardDaily, sortOrder = categories.size, externalId = "cashew:${c.pk}",
            )
        }
        if (merged.isNotEmpty()) issues += ImportIssue.MergedCategories(merged.distinct())
        val categoryById = categories.associateBy { it.id }

        // People, one per loan the user made a person. Their starting balance is the loan itself when Cashew only
        // tracked the payments (no entry for the money that first changed hands).
        val people = mutableListOf<Person>()
        val personIds = HashMap<String, Long>()
        val personLoans = mutableListOf<LoanEntry>()
        val all = source.transactions()
        val paid = all.filter { it.recorded }
        for (o in loans.values.filter { choices.loans[it.pk] == LoanChoice.PERSON }.sortedBy { it.epochMillis }) {
            val name = fit(o.name, InputRules.NAME_MAX).ifBlank { "Loan" }
            val existing = people.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val id = existing?.id ?: (people.size + 1L).also { people += Person(id = it, name = name, archived = o.archived) }
            personIds[o.pk] = id
            if (paid.none { it.objectiveLoanPk == o.pk && it.isPrincipalOf(o) }) {
                val amount = kotlin.math.abs(cents(o.amount) ?: 0L)
                if (amount != 0L) personLoans += LoanEntry(id = personLoans.size + 1L, personId = id, amount = if (o.income) amount else -amount, epochMillis = o.epochMillis, note = "Starting balance from Cashew")
            }
        }
        // The same for loans that became accounts: their starting balance is the loan when only payments were tracked.
        for (o in loanAccounts) {
            val id = loanAccountIds.getValue(o.pk)
            if (paid.none { it.objectiveLoanPk == o.pk && it.isPrincipalOf(o) }) {
                val amount = kotlin.math.abs(cents(o.amount) ?: 0L)
                opening[id] = (opening[id] ?: 0L) + if (o.income) amount else -amount
            }
        }

        // Entries
        val transactions = mutableListOf<Transaction>()
        var nextTx = 1L
        var badAmount = 0
        var noAccount = 0
        var tooLarge = 0
        var zero = 0
        var refunds = 0
        var corrections = 0
        var transfersAsEntries = 0
        var mismatched = 0
        val walletNames = source.wallets().associate { it.pk to fit(it.name, InputRules.NAME_MAX) }
        val pairs = transferPairs(paid)
        val inPair = HashMap<String, Pair<CwTransaction, CwTransaction>>()
        pairs.forEach { inPair[it.first.pk] = it; inPair[it.second.pk] = it }
        val done = HashSet<String>()
        fun add(t: Transaction) { transactions += t.copy(id = nextTx++) }

        for (r in paid.sortedWith(compareBy({ it.epochMillis }, { it.pk }))) {
            if (r.pk in done) continue
            val walletId = r.walletPk?.let { accountIds[it] }
            if (walletId == null) {
                // Rows of an account the user left out simply stay in Cashew; ones pointing nowhere are counted.
                if (r.walletPk == null || source.wallets().none { it.pk == r.walletPk }) noAccount++
                continue
            }
            val signed = cents(r.amount)
            if (signed == null) { badAmount++; continue }
            val amount = kotlin.math.abs(signed)
            if (amount > InputRules.AMOUNT_MAX) { tooLarge++; continue }
            val note = noteOf(r)

            if (r.categoryPk == BALANCE_CORRECTION) {
                val pair = inPair[r.pk]
                if (pair != null) {
                    val (out, into) = pair
                    done += out.pk; done += into.pk
                    val from = out.walletPk?.let { accountIds[it] }
                    val to = into.walletPk?.let { accountIds[it] }
                    val outCents = cents(out.amount)?.let { kotlin.math.abs(it) }
                    val inCents = cents(into.amount)?.let { kotlin.math.abs(it) }
                    when {
                        from != null && to != null && outCents != null && inCents != null -> {
                            if (outCents != inCents) {
                                // Different currencies: keep each account right with the sending amount moved and the
                                // difference left as a correction on the receiving side.
                                mismatched++
                                opening[to] = (opening[to] ?: 0L) + (inCents - outCents)
                            }
                            add(Transaction(kind = TxKind.TRANSFER, amount = outCents, accountId = from, toAccountId = to, epochMillis = out.epochMillis, note = noteOf(out), externalId = "cashew:${out.pk}"))
                        }
                        else -> {
                            // The other account was left out: keep this side as a plain entry so this balance matches.
                            transfersAsEntries++
                            val mine = if (from != null) out else into
                            val other = if (from != null) into else out
                            val c = cents(mine.amount) ?: continue
                            val text = fit(listOf(noteOf(mine), "Transfer with ${walletNames[other.walletPk] ?: "another account"}").filter { it.isNotBlank() }.joinToString(" · "), InputRules.NOTE_MAX)
                            add(Transaction(kind = if (c < 0) TxKind.EXPENSE else TxKind.INCOME, amount = kotlin.math.abs(c), accountId = from ?: to, epochMillis = mine.epochMillis, note = text, externalId = "cashew:${mine.pk}"))
                        }
                    }
                } else {
                    corrections++
                    opening[walletId] = (opening[walletId] ?: 0L) + signed
                }
                continue
            }

            val loan = r.objectiveLoanPk?.let { loans[it] }
            when (loan?.let { choices.loans[it.pk] }) {
                LoanChoice.PERSON -> {
                    add(Transaction(kind = if (signed < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN, amount = amount, accountId = walletId, epochMillis = r.epochMillis, note = note, personId = personIds.getValue(loan.pk), externalId = "cashew:${r.pk}"))
                    continue
                }
                LoanChoice.ACCOUNT -> {
                    val loanAccount = loanAccountIds.getValue(loan.pk)
                    add(
                        if (signed < 0) Transaction(kind = TxKind.TRANSFER, amount = amount, accountId = walletId, toAccountId = loanAccount, epochMillis = r.epochMillis, note = note, externalId = "cashew:${r.pk}")
                        else Transaction(kind = TxKind.TRANSFER, amount = amount, accountId = loanAccount, toAccountId = walletId, epochMillis = r.epochMillis, note = note, externalId = "cashew:${r.pk}"),
                    )
                    continue
                }
                else -> Unit
            }

            if (amount == 0L) zero++
            val categoryId = (r.subCategoryPk?.let { catIds[it] } ?: r.categoryPk?.let { catIds[it] })
            val kind = if (signed < 0) TxKind.EXPENSE else TxKind.INCOME
            if (kind == TxKind.INCOME && categoryId?.let { categoryById[it]?.kind } == CategoryKind.EXPENSE) refunds++
            add(Transaction(kind = kind, amount = amount, accountId = walletId, categoryId = categoryId, epochMillis = r.epochMillis, note = note, externalId = "cashew:${r.pk}"))
        }
        val finalAccounts = accounts.map { it.copy(openingBalance = opening[it.id] ?: 0L) }

        // Bills: the next unpaid occurrence of each monthly subscription or repeating expense.
        val upcoming = all.filter { !it.paid && !it.skipPaid }
        val bills = mutableListOf<Recurring>()
        var otherRepeats = 0
        for (r in upcoming.filter { it.type == SUBSCRIPTION || it.type == REPETITIVE }.sortedBy { it.epochMillis }) {
            val accountId = r.walletPk?.let { accountIds[it] }
            val categoryId = r.subCategoryPk?.let { catIds[it] } ?: r.categoryPk?.let { catIds[it] }
            val signed = cents(r.amount)
            val monthly = r.reoccurrence == MONTHLY && (r.periodLength ?: 1) == 1
            if (accountId == null || categoryId == null || signed == null || signed >= 0 || !monthly) { otherRepeats++; continue }
            val name = fit(r.title.ifBlank { categoryById[categoryId]?.name.orEmpty() }, InputRules.NAME_MAX)
            if (bills.any { it.name.equals(name, ignoreCase = true) && it.accountId == accountId }) continue
            bills += Recurring(
                id = bills.size + 1L, name = name, categoryId = categoryId, accountId = accountId,
                dayOfMonth = java.time.Instant.ofEpochMilli(r.epochMillis).atZone(zone).dayOfMonth, fixedAmount = kotlin.math.abs(signed),
            )
        }
        val repeatingPks = upcoming.filter { it.type == SUBSCRIPTION || it.type == REPETITIVE }
        val stillUpcoming = upcoming.size - repeatingPks.size

        // Budgets: category limits from monthly budgets that are in use; the first budget wins a category.
        val budgets = mutableListOf<Budget>()
        var otherLimits = 0
        val cwBudgets = source.budgets().associateBy { it.pk }
        val limits = source.categoryLimits().sortedBy { cwBudgets[it.budgetPk]?.order ?: Int.MAX_VALUE }
        for (l in limits) {
            val b = cwBudgets[l.budgetPk]
            val categoryId = catIds[l.categoryPk]
            val category = categoryId?.let { categoryById[it] }
            val monthly = b != null && !b.archived && b.reoccurrence == MONTHLY && b.periodLength == 1
            if (!monthly || category == null || category.parentId != null || category.kind != CategoryKind.EXPENSE) { otherLimits++; continue }
            val raw = cents(l.amount)
            if (raw == null) { otherLimits++; continue }
            val amount = if (b!!.absoluteLimits) kotlin.math.abs(raw) else kotlin.math.abs((cents(b.amount) ?: 0L) * raw / 10_000)
            if (amount <= 0L || amount > InputRules.AMOUNT_MAX) { otherLimits++; continue }
            if (budgets.none { it.categoryId == categoryId }) budgets += Budget(categoryId = categoryId, month = 0, amount = amount)
        }

        // What the review says
        val goals = objectives.count { it.type == GOAL }
        if (stillUpcoming > 0) issues += ImportIssue.LeftBehind(stillUpcoming, "Upcoming entries not marked paid yet")
        if (otherRepeats > 0) issues += ImportIssue.LeftBehind(otherRepeats, "Repeating entries that aren't a monthly expense (Kassa's bills are monthly)")
        if (goals > 0) issues += ImportIssue.LeftBehind(goals, "Goals (their entries still come in as normal entries)")
        if (otherLimits > 0) issues += ImportIssue.LeftBehind(otherLimits, "Budget limits not on a main spending category of a monthly budget")
        if (source.budgets().isNotEmpty()) issues += ImportIssue.LeftBehind(source.budgets().size, "Budget totals (only their category limits come over)")
        if (corrections > 0) issues += ImportIssue.BalanceCorrections(corrections)
        if (transfersAsEntries > 0) issues += ImportIssue.TransfersAsEntries(transfersAsEntries)
        if (mismatched > 0) issues += ImportIssue.MismatchedTransfers(mismatched)
        if (refunds > 0) issues += ImportIssue.RefundsAsIncome(refunds)
        if (zero > 0) issues += ImportIssue.ZeroAmountEntries(zero)
        if (noAccount > 0) issues += ImportIssue.SkippedRows(noAccount, "entries whose account no longer exists in Cashew")
        if (badAmount > 0) issues += ImportIssue.SkippedRows(badAmount, "entries with an unreadable amount")
        if (tooLarge > 0) issues += ImportIssue.SkippedRows(tooLarge, "entries above the largest amount Kassa allows")

        return ImportPlan(
            groups = emptyList(),
            accounts = finalAccounts,
            categories = categories,
            transactions = transactions,
            budgets = budgets,
            issues = issues,
            reconcile = reconcile(paid, wallets, accountIds, finalAccounts, transactions),
            people = people,
            personLoans = personLoans,
            bills = bills,
            sourceName = "Cashew backup",
            appName = "Cashew",
        )
    }

    /**
     * Each kept account's balance in Cashew (every paid entry in it) against the balance Kassa will show. Loan accounts
     * aren't in Cashew, so they aren't checked. Months aren't compared: loans and transfers change what counts as
     * spending, so balances are the check.
     */
    private fun reconcile(
        paid: List<CwTransaction>, wallets: List<CwWallet>, accountIds: Map<String, Long>,
        accounts: List<Account>, imported: List<Transaction>,
    ): Reconcile {
        val byId = accounts.associateBy { it.id }
        val checks = wallets.map { w ->
            val id = accountIds.getValue(w.pk)
            val source = paid.filter { it.walletPk == w.pk }.sumOf { cents(it.amount)?.takeIf { c -> kotlin.math.abs(c) <= InputRules.AMOUNT_MAX } ?: 0L }
            Reconcile.AccountCheck(id, byId.getValue(id).name, source, balanceOf(byId.getValue(id), imported))
        }
        return Reconcile(checks, emptyList())
    }

    companion object {
        /** Cashew's Balance Correction category, used for corrections and for transfers between accounts. */
        const val BALANCE_CORRECTION = "0"
        private const val SUBSCRIPTION = 1
        private const val REPETITIVE = 2
        private const val MONTHLY = 3
        private const val GOAL = 0
        private const val LOAN = 1

        private val CwTransaction.recorded: Boolean get() = paid && !skipPaid

        /** Cents from Cashew's decimal text, or null when it isn't a number. */
        fun cents(text: String): Long? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val negative = t.startsWith("-")
            val value = Money.parseCents(t.removePrefix("-").removePrefix("+")) ?: return null
            return if (negative) -value else value
        }

        /** The money that first changed hands for a loan: out of your account when you lent, into it when you borrowed. */
        private fun CwTransaction.isPrincipalOf(loan: CwObjective): Boolean {
            val c = cents(amount) ?: return false
            return if (loan.income) c < 0 else c > 0
        }

        /**
         * Transfers in the paid entries: two Balance Correction entries linked to each other, or, for older backups that
         * didn't link them, one out and one in of the same size in different accounts within a minute. Out side first.
         */
        fun transferPairs(paid: List<CwTransaction>): List<Pair<CwTransaction, CwTransaction>> {
            val corrections = paid.filter { it.categoryPk == BALANCE_CORRECTION }
            val byPk = corrections.associateBy { it.pk }
            val used = HashSet<String>()
            val pairs = mutableListOf<Pair<CwTransaction, CwTransaction>>()
            fun take(a: CwTransaction, b: CwTransaction) {
                val (out, into) = if ((cents(a.amount) ?: 0L) <= 0L) a to b else b to a
                pairs += out to into; used += a.pk; used += b.pk
            }
            for (t in corrections) {
                if (t.pk in used) continue
                val other = t.pairedPk?.let { byPk[it] } ?: corrections.firstOrNull { it.pairedPk == t.pk && it.pk != t.pk }
                if (other != null && other.pk !in used && other.walletPk != t.walletPk) take(t, other)
            }
            val loose = corrections.filter { it.pk !in used && it.pairedPk == null }.sortedBy { it.epochMillis }
            for (t in loose) {
                if (t.pk in used) continue
                val c = cents(t.amount) ?: continue
                val other = loose.firstOrNull {
                    it.pk != t.pk && it.pk !in used && it.walletPk != t.walletPk &&
                        cents(it.amount) == -c && c != 0L && kotlin.math.abs(it.epochMillis - t.epochMillis) <= 60_000
                }
                if (other != null) take(t, other)
            }
            return pairs
        }

        /** Title first, then the note, cut to fit. */
        fun noteOf(r: CwTransaction): String =
            fit(listOf(r.title, r.note).map { InputRules.clean(it) }.filter { it.isNotBlank() }.distinct().joinToString(" · "), InputRules.NOTE_MAX)

        /** Cashew stores colours as "0xffRRGGBB". */
        fun colorOf(text: String?): Int? {
            val hex = text?.trim()?.removePrefix("0x")?.removePrefix("#") ?: return null
            val value = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000L or value).toInt()
                8 -> value.toInt()
                else -> null
            }
        }

        fun iconFor(c: CwCategory): CategoryIcon {
            c.emoji?.trim()?.takeIf { it.isNotEmpty() }?.let { return CategoryIcon.Emoji(it) }
            val key = c.iconName?.lowercase()?.substringBefore(".png")?.replace(Regex("\\(\\d+\\)"), "")?.trim() ?: return CategoryIcon.DEFAULT
            return ICONS[key]?.let { CategoryIcon.Symbol(it) } ?: CategoryIcon.DEFAULT
        }

        /** Cashew's bundled pictures to Kassa's symbols, for the ones with a clear match. */
        private val ICONS = mapOf(
            "cutlery" to "restaurant", "groceries" to "grocery", "shopping" to "bag", "shopping-bag" to "bag", "tram" to "train",
            "train" to "train", "bus" to "bus", "popcorn" to "movie", "bills" to "bills", "bill" to "bills", "gift" to "gift",
            "flower" to "flowers", "bouquet" to "flowers", "briefcase" to "work", "plane" to "flight", "coin" to "payments",
            "dollar-coin" to "payments", "cash-bill-dollar" to "money", "bank" to "bank", "atm-machine" to "bank",
            "bicycle" to "bike", "bread" to "bakery", "cake" to "cake", "cupcake" to "cake", "car" to "car", "car-key" to "car",
            "car-charging-station" to "charging", "cat" to "pets", "dog" to "pets", "church" to "church", "cleaning" to "cleaning",
            "clothes-hanger" to "clothes", "dress" to "clothes", "coffee" to "coffee", "coffee-cup" to "coffee",
            "credit-card" to "card", "dental-care" to "medical", "double-bed" to "bed", "fast-food" to "fastfood",
            "charts" to "chart", "celebration" to "party", "confetti" to "party", "balloons" to "party", "christmas-tree" to "party",
            "delivery-truck" to "shipping", "box" to "package", "diamond" to "jewelry", "earth" to "map", "compass" to "map",
            "barber" to "haircut", "beach-umbrella" to "beach", "cottage" to "house", "cabin" to "house", "house" to "house",
            "bottles" to "drink", "bubble-tea" to "drink", "calculator" to "receipt", "camera" to "camera",
            "desktop-computer" to "computer", "curry" to "lunch", "eggs" to "grocery", "campfire" to "hiking",
            "graduation" to "school", "sports" to "gym", "ball" to "soccer", "bowling" to "games", "dice" to "games",
            "chess" to "games", "crypto" to "trending", "envelope" to "receipt", "essay" to "receipt", "clipboard" to "receipt",
        )
    }
}
