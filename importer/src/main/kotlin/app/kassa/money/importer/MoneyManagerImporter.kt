package app.kassa.money.importer

import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountGroup
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Budget
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.balanceOf
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Everything an import would write, plus what the review screen shows before anything is saved.
 * Ids are assigned here, starting at 1, and are written as-is into an empty database.
 */
data class ImportPlan(
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val categories: List<Category>,
    val transactions: List<Transaction>,
    val budgets: List<Budget>,
    val issues: List<ImportIssue>,
    val reconcile: Reconcile,
)

sealed interface ImportIssue {
    /** Accounts whose names look like the same account entered twice, e.g. "Amex Platinum" and "amex plat". */
    data class PossibleDuplicateAccounts(val names: List<String>) : ImportIssue
    /** Splitwise-style accounts or categories. Kassa tracks these as money owed by or to people instead. */
    data class SplitwiseRows(val accountNames: List<String>, val categoryNames: List<String>, val entries: Int) : ImportIssue
    /** A transfer whose two halves disagree on amount, date or accounts. The "out" half is used. */
    data class MismatchedTransfers(val count: Int) : ImportIssue
    /** A transfer half with no partner. Imported as a one-sided transfer so balances still match. */
    data class UnpairedTransfers(val count: Int) : ImportIssue
    data class ZeroAmountEntries(val count: Int) : ImportIssue
    data class SkippedRows(val count: Int, val reason: String) : ImportIssue
    /** Categories whose names differ only in capitals ("Phone bill", "Phone Bill"), merged into the first. */
    data class MergedCategories(val names: List<String>) : ImportIssue
}

/**
 * Balances and monthly totals computed two ways: straight from the backup's rows using Money Manager's own
 * rules, and from what Kassa is about to save. Any difference means the mapping lost or changed something.
 */
data class Reconcile(
    val accounts: List<AccountCheck>,
    val months: List<MonthCheck>,
) {
    data class AccountCheck(val accountId: Long, val name: String, val source: Long, val imported: Long) {
        val matches: Boolean get() = source == imported
    }

    data class MonthCheck(
        val month: YearMonth,
        val sourceExpense: Long, val importedExpense: Long,
        val sourceIncome: Long, val importedIncome: Long,
    ) {
        val matches: Boolean get() = sourceExpense == importedExpense && sourceIncome == importedIncome
    }

    val allMatch: Boolean get() = accounts.all { it.matches } && months.all { it.matches }
}

class MoneyManagerImporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun plan(source: MoneyManagerSource): ImportPlan {
        val skipped = mutableListOf<ImportIssue>()

        // Groups
        val mmGroups = source.groups().sortedBy { it.order }
        val groupIds = HashMap<String, Long>()
        val groups = mmGroups.mapIndexed { i, g ->
            val id = (i + 1).toLong()
            groupIds[g.uid] = id
            AccountGroup(id = id, name = fit(g.name, InputRules.NAME_MAX), sortOrder = i)
        }
        val groupType = mmGroups.associate { it.uid to it.type }

        // Accounts
        val accountIds = HashMap<String, Long>()
        // Kassa needs every account name to be unique, so a repeated Money Manager name gets " 2", " 3" and so on.
        val accountNames = uniqueNames(source.assets().sortedBy { it.order }.map { fit(it.name, InputRules.NAME_MAX - 3) })
        val accounts = source.assets().sortedBy { it.order }.mapIndexed { i, a ->
            val id = (i + 1).toLong()
            accountIds[a.uid] = id
            val type = accountTypeFor(groupType[a.groupUid], a.name)
            Account(
                id = id,
                name = accountNames[i],
                type = type,
                groupId = a.groupUid?.let { groupIds[it] },
                openingBalance = a.startingAmount?.let { Money.parseCents(it) } ?: 0L,
                statementDay = if (type.hasStatement) a.statementDay?.takeIf { it in 1..31 } else null,
                dueDay = if (type.hasStatement) a.dueDay?.takeIf { it in 1..31 } else null,
                sortOrder = i,
                externalId = a.uid,
            )
        }
        val defaultPayFrom = accounts.firstOrNull { it.type == AccountType.CHECKING }?.id
        val accountsWithPayFrom = accounts.map {
            if (it.type == AccountType.CREDIT_CARD && it.payFromId == null) it.copy(payFromId = defaultPayFrom) else it
        }

        // Categories: parents first so children can point at them.
        val mmCats = source.categories()
        val parents = mmCats.filter { it.parentUid.isNullOrBlank() || it.parentUid == "0" }.sortedWith(compareBy({ it.type }, { it.order }))
        val children = mmCats.filter { !(it.parentUid.isNullOrBlank() || it.parentUid == "0") }.sortedBy { it.order }
        val catIds = HashMap<String, Long>()
        val categories = mutableListOf<Category>()
        var nextCat = 1L
        // Names are unique per parent ignoring case, as in Kassa; a repeat folds into the first one.
        val merged = mutableListOf<String>()
        fun sameName(kind: CategoryKind, parentId: Long?, name: String) =
            categories.firstOrNull { it.kind == kind && it.parentId == parentId && it.name.equals(name, ignoreCase = true) }
        fun fold(uid: String, into: Category, visible: Boolean) {
            catIds[uid] = into.id
            merged += into.name
            if (visible && into.hidden) categories[categories.indexOf(into)] = into.copy(hidden = false)
        }
        parents.forEachIndexed { i, c ->
            val (icon, name) = splitEmoji(c.name)
            val kind = if (c.type == 0) CategoryKind.INCOME else CategoryKind.EXPENSE
            sameName(kind, null, name)?.let { fold(c.uid, it, !c.deleted); return@forEachIndexed }
            val id = nextCat++
            catIds[c.uid] = id
            categories += Category(
                id = id,
                name = name,
                kind = kind,
                icon = icon,
                color = PALETTE[i % PALETTE.size],
                countsTowardDaily = c.type != 0 && !looksLikeBill(name),
                hidden = c.deleted,
                sortOrder = i,
                externalId = c.uid,
            )
        }
        children.forEachIndexed { i, c ->
            val parentId = catIds[c.parentUid]
            if (parentId == null) {
                skipped += ImportIssue.SkippedRows(1, "Subcategory \"${c.name.trim()}\" points at a missing parent")
                return@forEachIndexed
            }
            val parent = categories.first { it.id == parentId }
            val (icon, name) = splitEmoji(c.name)
            sameName(parent.kind, parentId, name)?.let { fold(c.uid, it, !(c.deleted || parent.hidden)); return@forEachIndexed }
            val id = nextCat++
            catIds[c.uid] = id
            categories += Category(
                id = id,
                name = name,
                kind = parent.kind,
                parentId = parentId,
                icon = if (icon == CategoryIcon.DEFAULT) parent.icon else icon,
                color = parent.color,
                countsTowardDaily = parent.countsTowardDaily,
                hidden = c.deleted || parent.hidden,
                sortOrder = i,
                externalId = c.uid,
            )
        }

        // Transactions
        val rows = source.transactions().sortedBy { it.epochMillis }
        val outByPair = rows.filter { it.doType == 3 && !it.pairKey.isNullOrBlank() }.associateBy { it.pairKey!! }
        val inByPair = rows.filter { it.doType == 4 && !it.pairKey.isNullOrBlank() }.associateBy { it.pairKey!! }
        var mismatched = 0
        var unpaired = 0
        var zero = 0
        var badRows = 0
        val transactions = mutableListOf<Transaction>()
        var nextTx = 1L
        for (r in rows) {
            val amount = Money.parseCents(r.amount)
            val accountId = accountIds[r.assetUid]
            if (amount == null || accountId == null) { badRows++; continue }
            if (amount == 0L) zero++
            val note = fit(r.note.orEmpty(), InputRules.NOTE_MAX)
            val tx = when (r.doType) {
                0, 1 -> {
                    val cat = r.categoryUid?.let { catIds[it] }
                    Transaction(
                        id = nextTx, kind = if (r.doType == 0) TxKind.INCOME else TxKind.EXPENSE, amount = amount,
                        accountId = accountId, categoryId = cat, epochMillis = r.epochMillis, note = note, externalId = r.uid,
                    )
                }
                3 -> {
                    val partner = r.pairKey?.let { inByPair[it] }
                    if (partner != null && !mirrors(r, partner)) mismatched++
                    val to = r.toAssetUid?.let { accountIds[it] } ?: partner?.assetUid?.let { accountIds[it] }
                    if (to == null) { badRows++; null } else {
                        if (partner == null) unpaired++
                        Transaction(id = nextTx, kind = TxKind.TRANSFER, amount = amount, accountId = accountId, toAccountId = to,
                            epochMillis = r.epochMillis, note = note, externalId = r.uid)
                    }
                }
                4 -> {
                    if (r.pairKey != null && outByPair.containsKey(r.pairKey)) null // the "out" half carries it
                    else {
                        val from = r.toAssetUid?.let { accountIds[it] }
                        if (from == null) { badRows++; null } else {
                            unpaired++
                            Transaction(id = nextTx, kind = TxKind.TRANSFER, amount = amount, accountId = from, toAccountId = accountId,
                                epochMillis = r.epochMillis, note = note, externalId = r.uid)
                        }
                    }
                }
                else -> { badRows++; null }
            }
            if (tx != null) { transactions += tx; nextTx++ }
        }

        // Budgets
        val budgets = source.budgets().mapNotNull { b ->
            val cat = catIds[b.categoryUid] ?: return@mapNotNull null
            Budget(categoryId = cat, month = b.period, amount = Math.round(b.amount * 100))
        }.distinctBy { it.categoryId to it.month } // merged categories keep the first budget

        // Review items
        val issues = mutableListOf<ImportIssue>()
        if (merged.isNotEmpty()) issues += ImportIssue.MergedCategories(merged.distinct())
        // Only compare accounts of the same type: "SoFi" and "SoFi Savings" are different accounts.
        accountsWithPayFrom.groupBy { it.type }.values.forEach { sameType ->
            duplicateNames(sameType.map { it.name }).forEach { issues += ImportIssue.PossibleDuplicateAccounts(it) }
        }
        val swAccounts = accountsWithPayFrom.filter { it.name.contains("splitwise", true) }
        val swCats = categories.filter { it.name.contains("splitwise", true) }
        if (swAccounts.isNotEmpty() || swCats.isNotEmpty()) {
            val accIds = swAccounts.map { it.id }.toSet()
            val catSet = swCats.map { it.id }.toSet()
            val entries = transactions.count { it.accountId in accIds || it.toAccountId in accIds || it.categoryId in catSet }
            issues += ImportIssue.SplitwiseRows(swAccounts.map { it.name }, swCats.map { it.name }, entries)
        }
        if (mismatched > 0) issues += ImportIssue.MismatchedTransfers(mismatched)
        if (unpaired > 0) issues += ImportIssue.UnpairedTransfers(unpaired)
        if (zero > 0) issues += ImportIssue.ZeroAmountEntries(zero)
        if (badRows > 0) issues += ImportIssue.SkippedRows(badRows, "Rows with an unreadable amount or a missing account")
        issues += skipped

        return ImportPlan(
            groups = groups,
            accounts = accountsWithPayFrom,
            categories = categories,
            transactions = transactions,
            budgets = budgets,
            issues = issues,
            reconcile = reconcile(rows, accountsWithPayFrom, accountIds, transactions),
        )
    }

    private fun reconcile(
        rows: List<MmTransaction>,
        accounts: List<Account>,
        accountIds: Map<String, Long>,
        imported: List<Transaction>,
    ): Reconcile {
        // Money Manager's own rule: every row moves its own account; transfers are already two rows.
        val sourceBalance = HashMap<Long, Long>()
        val srcExp = HashMap<YearMonth, Long>()
        val srcInc = HashMap<YearMonth, Long>()
        for (r in rows) {
            val id = accountIds[r.assetUid] ?: continue
            val amt = Money.parseCents(r.amount) ?: continue
            val delta = when (r.doType) { 0, 4 -> amt; 1, 3 -> -amt; else -> 0 }
            sourceBalance[id] = (sourceBalance[id] ?: 0) + delta
            val m = monthOf(r.epochMillis)
            if (r.doType == 1) srcExp[m] = (srcExp[m] ?: 0) + amt
            if (r.doType == 0) srcInc[m] = (srcInc[m] ?: 0) + amt
        }
        val accountChecks = accounts.map {
            Reconcile.AccountCheck(it.id, it.name, it.openingBalance + (sourceBalance[it.id] ?: 0), balanceOf(it, imported))
        }
        val impExp = HashMap<YearMonth, Long>()
        val impInc = HashMap<YearMonth, Long>()
        for (t in imported) {
            val m = monthOf(t.epochMillis)
            if (t.kind == TxKind.EXPENSE) impExp[m] = (impExp[m] ?: 0) + t.amount
            if (t.kind == TxKind.INCOME) impInc[m] = (impInc[m] ?: 0) + t.amount
        }
        val months = (srcExp.keys + srcInc.keys + impExp.keys + impInc.keys).toSortedSet().map {
            Reconcile.MonthCheck(it, srcExp[it] ?: 0, impExp[it] ?: 0, srcInc[it] ?: 0, impInc[it] ?: 0)
        }
        return Reconcile(accountChecks, months)
    }

    private fun monthOf(millis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

    private fun mirrors(out: MmTransaction, inRow: MmTransaction): Boolean =
        out.amount == inRow.amount && out.epochMillis == inRow.epochMillis &&
            out.assetUid == inRow.toAssetUid && out.toAssetUid == inRow.assetUid

    companion object {
        /** Category colors, chosen to read on both light and dark backgrounds. */
        val PALETTE = intArrayOf(
            0xFF3F9A5E.toInt(), 0xFF2B8A99.toInt(), 0xFF4570D6.toInt(), 0xFFC98A1E.toInt(),
            0xFFC4487F.toInt(), 0xFF7A5BD4.toInt(), 0xFFE07B53.toInt(), 0xFF9C6B3F.toInt(),
            0xFF3D8FC7.toInt(), 0xFF2E9C8A.toInt(), 0xFFA0527A.toInt(), 0xFF8A9F35.toInt(),
            0xFFC2453F.toInt(), 0xFF6F7C8F.toInt(),
        )

        private val BILL_WORDS = listOf("mortgage", "home standard", "phone", "subscription", "bill", "insurance", "hoa", "rent", "utilit")

        fun looksLikeBill(name: String): Boolean = BILL_WORDS.any { name.contains(it, ignoreCase = true) }

        /** Money Manager group type to Kassa account type. Unknown types fall back to the group name. */
        fun accountTypeFor(groupType: Int?, accountName: String): AccountType = when (groupType) {
            1, 3 -> AccountType.CHECKING
            2 -> AccountType.CREDIT_CARD
            4 -> AccountType.SAVINGS
            5 -> AccountType.LOAN
            6 -> AccountType.PREPAID
            8 -> AccountType.INVESTMENT
            9 -> AccountType.OTHER_DEBT
            11 -> AccountType.CASH
            7, 10 -> AccountType.OTHER_ASSET
            else -> if (accountName.contains("card", true)) AccountType.CREDIT_CARD else AccountType.OTHER_ASSET
        }

        /** A name or note as Kassa stores it: tidied by [InputRules.clean] and cut to [max] characters. */
        fun fit(raw: String, max: Int): String = InputRules.clean(raw).take(max).trim()

        /** [names] with repeats (ignoring case) numbered: "Cash", "Cash 2", "Cash 3". */
        fun uniqueNames(names: List<String>): List<String> {
            val used = HashSet<String>()
            return names.map { name ->
                var candidate = name
                var n = 2
                while (!used.add(candidate.lowercase())) candidate = "$name ${n++}"
                candidate
            }
        }

        /**
         * "🛒Groceries " becomes (Emoji("🛒"), "Groceries"). Names without a leading symbol keep the default icon.
         * The name is cut to [InputRules.CATEGORY_NAME_MAX].
         */
        fun splitEmoji(raw: String): Pair<CategoryIcon, String> {
            val (icon, name) = splitLeadingSymbol(raw.trim())
            return icon to fit(name, InputRules.CATEGORY_NAME_MAX)
        }

        private fun splitLeadingSymbol(text: String): Pair<CategoryIcon, String> {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) break
                i += Character.charCount(cp)
            }
            val prefix = text.substring(0, i)
            val name = text.substring(i).trim()
            return when {
                prefix.isBlank() -> CategoryIcon.DEFAULT to text
                name.isEmpty() -> CategoryIcon.Emoji(prefix) to text
                else -> CategoryIcon.Emoji(prefix) to name
            }
        }

        /** Groups names that look like the same account: equal, or one a prefix of the other, ignoring case and spaces. */
        fun duplicateNames(names: List<String>): List<List<String>> {
            fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val out = mutableListOf<List<String>>()
            val used = BooleanArray(names.size)
            for (i in names.indices) {
                if (used[i]) continue
                val a = norm(names[i])
                if (a.length < 3) continue
                val group = mutableListOf(names[i])
                for (j in i + 1 until names.size) {
                    val b = norm(names[j])
                    if (b.length >= 3 && (a.startsWith(b) || b.startsWith(a))) { group += names[j]; used[j] = true }
                }
                if (group.size > 1) out += group
            }
            return out
        }
    }
}
