package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the iOS write path asks of :shared: entries, accounts, people, first run and the pickers. The rules themselves are tested in :domain. */
class WriteBridgeTest {

    private fun entry(
        kind: String = "EXPENSE", amount: Long = 1250, account: Long = 2, to: Long = 0, category: Long = 1, note: String = "Lunch", id: Long = 0,
    ) = EntryInput(id, kind, amount, account, to, category, 1_767_268_800_000L, note)

    private fun cells(input: EntryInput): Map<String, String?> {
        val names = BackupRows.columns("transactions")
        return names.indices.associate { names[it] to EntryBridge.cell(input, it) }
    }

    // ---------- entries ----------

    @Test
    fun problemsAreAndroidsSentencesInTheLedgersCurrency() {
        KassaCore.installDefaults()
        assertNull(EntryBridge.problem(entry(), "USD"))
        assertEquals(FieldProblemData("AMOUNT", "Enter an amount."), EntryBridge.problem(entry(amount = 0), "USD"))
        assertEquals(FieldProblemData("CATEGORY", "Pick a category."), EntryBridge.problem(entry(category = 0), "USD"))
        assertEquals(FieldProblemData("ACCOUNT", "Pick an account."), EntryBridge.problem(entry(account = 0), "USD"))
        assertEquals(FieldProblemData("TO", "Pick where the money went."), EntryBridge.problem(entry(kind = "TRANSFER", category = 0), "USD"))
        assertEquals(FieldProblemData("TO", "Pick where the money went."), EntryBridge.problem(entry(kind = "TRANSFER", category = 0, to = 2), "USD"))
        assertNull(EntryBridge.problem(entry(kind = "TRANSFER", category = 0, to = 3), "USD"))
        assertEquals("That's more than £100,000,000.", EntryBridge.problem(entry(amount = 10_000_000_000), "GBP")?.message)
        assertEquals("NOTE", EntryBridge.problem(entry(note = "x".repeat(121)), "USD")?.field)
        assertEquals("AMOUNT", EntryBridge.problem(entry(kind = "BUY"), "USD")?.field)
        assertEquals("AMOUNT", EntryBridge.problem(entry(kind = "nonsense"), "USD")?.field)
    }

    @Test
    fun aProblemMessageIsNeverMaskedAndMoneyIsPutBack() {
        val before = Money.style
        Money.hideAmounts = { true }
        try {
            assertEquals("That's more than $100,000,000.", EntryBridge.problem(entry(amount = 10_000_000_000), "USD")?.message)
            assertTrue(Money.isHidden, "the caller's switch is put back")
        } finally {
            Money.hideAmounts = { false }
        }
        assertEquals(before, Money.style)
    }

    @Test
    fun missingNamesTheNextInput() {
        assertEquals("CATEGORY", EntryBridge.missing("EXPENSE", 0, 0, 0))
        assertEquals("ACCOUNT", EntryBridge.missing("INCOME", 4, 0, 0))
        assertEquals("", EntryBridge.missing("EXPENSE", 4, 2, 0))
        assertEquals("ACCOUNT", EntryBridge.missing("TRANSFER", 0, 0, 0))
        assertEquals("TO", EntryBridge.missing("TRANSFER", 0, 2, 0))
        assertEquals("", EntryBridge.missing("TRANSFER", 0, 2, 3))
    }

    @Test
    fun anEntryBecomesTheStoredCellsInTheColumnOrderOfTheTable() {
        assertEquals(
            mapOf(
                "id" to null, "kind" to "EXPENSE", "amount" to "1250", "accountId" to "2", "toAccountId" to null, "categoryId" to "1",
                "personId" to null, "epochMillis" to "1767268800000", "note" to "Lunch", "myShare" to null, "externalId" to null,
            ),
            cells(entry()),
        )
        val t = cells(entry(kind = "TRANSFER", to = 3, note = "  Move  it "))
        assertEquals("3", t["toAccountId"])
        assertNull(t["categoryId"], "a transfer has no category")
        assertEquals("Move it", t["note"])
        assertNull(cells(entry(kind = "EXPENSE", to = 3))["toAccountId"], "only a transfer has a destination")
        assertEquals(11, EntryBridge.cellCount())
    }

    @Test
    fun anEditOwnsOnlySevenColumns() {
        val names = BackupRows.columns("transactions")
        val owned = names.filterIndexed { i, _ -> EntryBridge.ownsColumn(i) }
        assertEquals(listOf("kind", "amount", "accountId", "toAccountId", "categoryId", "epochMillis", "note"), owned)
    }

    @Test
    fun onlyNewlyTouchedAccountsMustBeOpen() {
        assertEquals(listOf("2"), EntryBridge.mustBeOpen(entry(), 0, 0))
        assertEquals(listOf("2", "3"), EntryBridge.mustBeOpen(entry(kind = "TRANSFER", to = 3), 0, 0))
        assertEquals(emptyList(), EntryBridge.mustBeOpen(entry(kind = "TRANSFER", to = 3), 2, 3))
        assertEquals(listOf("4"), EntryBridge.mustBeOpen(entry(kind = "TRANSFER", to = 4), 2, 3))
        assertEquals(listOf("9"), EntryBridge.mustBeOpen(entry(account = 9), 2, 0))
    }

    @Test
    fun investmentTransfersWaitAndTheClosedSentenceIsAndroids() {
        assertTrue(EntryBridge.waitsForExplanation("TRANSFER", "INVESTMENT"))
        assertFalse(EntryBridge.waitsForExplanation("TRANSFER", "SAVINGS"))
        assertFalse(EntryBridge.waitsForExplanation("EXPENSE", "INVESTMENT"))
        assertFalse(EntryBridge.waitsForExplanation("nonsense", "INVESTMENT"))
        assertEquals("Old is closed. Reopen it first to add to it.", EntryBridge.closedMessage("Old"))
    }

    @Test
    fun theSavedMomentAndDayFollowTheZone() {
        val now = 1_767_268_800_000L // 2026-01-01 12:00 UTC
        assertEquals(now, EntryBridge.savedMillis(20_454, "UTC", now, 0))
        assertEquals(20_454 * 86_400_000L + 12 * 3_600_000L, EntryBridge.savedMillis(20_454, "UTC", now, 0))
        assertEquals(20_454 * 86_400_000L + 12 * 3_600_000L, EntryBridge.savedMillis(20_454, "UTC", now + 5_000, 0) - 5_000)
        assertEquals(20_454, EntryBridge.dayOf(now, "UTC"))
        assertEquals(20_454, EntryBridge.dayOf(now, "Asia/Tokyo"))
        assertEquals(20_455, EntryBridge.dayOf(now + 4 * 3_600_000L, "Asia/Tokyo"))
        assertEquals(20_454, EntryBridge.dayOf(now, "No/Such_Zone"), "an unknown zone reads as UTC")
    }

    @Test
    fun theNumberPadKeepsAPointAndShowsThePhonesMark() {
        KassaCore.installDefaults()
        var typed = ""
        for (key in listOf("1", "2", "3", "4", ".", "5")) typed = EntryBridge.press(typed, key)
        assertEquals("1234.5", typed)
        assertEquals("1,234.5", EntryBridge.display(typed))
        assertEquals(123_450L, EntryBridge.cents(typed))
        assertEquals("1234.50", EntryBridge.typedOf(123_450))
        assertEquals("123", listOf("del", "del", "del").fold(typed) { t, k -> EntryBridge.press(t, k) })
        assertEquals("a".repeat(120), EntryBridge.typedNote("a".repeat(130)))
        assertEquals("a b", EntryBridge.typedNote("a\nb"))
    }

    @Test
    fun switchingKindKeepsWhatStillFits() {
        // categoryId,accountId,toAccountId
        assertEquals("0,2,0", EntryBridge.switched("EXPENSE", "TRANSFER", 1, 2, 0, accountIsCard = false))
        assertEquals("0,0,0", EntryBridge.switched("EXPENSE", "TRANSFER", 1, 2, 0, accountIsCard = true))
        assertEquals("0,2,0", EntryBridge.switched("TRANSFER", "INCOME", 0, 2, 3, accountIsCard = false))
        assertEquals("1,2,0", EntryBridge.switched("EXPENSE", "EXPENSE", 1, 2, 0, accountIsCard = false))
    }

    // ---------- accounts and people ----------

    private fun account(
        type: String = "CHECKING", name: String = "Everyday", last4: String = "", balance: String = "", statement: String = "", due: String = "",
        limit: String = "", payFrom: Long = 0,
    ) = AccountInput(type, name, last4, balance, statement, due, limit, payFrom)

    private fun accountCells(input: AccountInput, sortOrder: Int = 7): Map<String, String?> {
        val names = BackupRows.columns("accounts")
        return names.indices.associate { names[it] to AccountBridge.cell(input, it, sortOrder) }
    }

    @Test
    fun theSheetOffersOnlyWhatIosBuilds() {
        val groups = AccountBridge.kindGroups()
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_AND_LOANS", "OTHER"), groups.map { it.key })
        val kinds = groups.flatMap { g -> g.kinds.map { it.key } }
        assertEquals(listOf("CHECKING", "SAVINGS", "CASH", "PREPAID", "CREDIT_CARD", "OTHER_ASSET", "OTHER_DEBT"), kinds)
        for (left in listOf("LOAN", "INVESTMENT", "FIXED_DEPOSIT")) assertFalse(left in kinds, left)
        val card = groups[1].kinds.single()
        assertTrue(card.hasStatement)
        assertTrue(card.isDebt)
        assertTrue(groups[2].kinds.last().isDebt)
        assertFalse(groups[0].kinds.first().isDebt)
    }

    @Test
    fun accountProblemsComeFromTheDomainFormInFieldOrder() {
        KassaCore.installDefaults()
        assertEquals(emptyList(), AccountBridge.problems(account(), emptyList(), "USD"))
        assertEquals(
            listOf(FieldProblemData("NAME", "Give the account a name.")),
            AccountBridge.problems(account(name = " "), emptyList(), "USD"),
        )
        assertEquals("everyday is already used.", AccountBridge.problems(account(name = "everyday"), listOf("Everyday"), "USD").single().message)
        val card = AccountBridge.problems(account(type = "CREDIT_CARD", last4 = "12", balance = "x", statement = "0", due = "32", limit = "-1"), emptyList(), "USD")
        assertEquals(listOf("LAST4", "BALANCE", "STATEMENT_DAY", "DUE_DAY", "LIMIT"), card.map { it.field })
        assertEquals("The statement day has to be 1 to 31.", card[2].message)
        assertEquals("Pick a kind of account.", AccountBridge.problems(account(type = "NOPE"), emptyList(), "USD").single().message)
        assertTrue(AccountBridge.problems(account(type = "CASH", balance = "-5"), emptyList(), "USD").isEmpty(), "overdrawn is fine")
    }

    @Test
    fun anAccountBecomesItsCellsWithTheBalanceAsTheStartingBalance() {
        KassaCore.installDefaults()
        val a = accountCells(account(type = "SAVINGS", name = " My  savings ", balance = "1,250.50", last4 = "4821"))
        assertEquals(
            mapOf(
                "id" to null, "name" to "My savings", "type" to "SAVINGS", "groupId" to null, "openingBalance" to "125050", "statementDay" to null,
                "dueDay" to null, "payFromId" to null, "creditLimit" to null, "archived" to "0", "sortOrder" to "7", "externalId" to null,
                "last4" to "4821", "maturityDay" to null, "closedDay" to null,
            ),
            a,
        )
        // A debt is stored negative; a card keeps its days, limit and pay-from.
        val c = accountCells(account(type = "CREDIT_CARD", name = "Card", balance = "300", statement = "5", due = "28", limit = "5000", payFrom = 3))
        assertEquals("-30000", c["openingBalance"])
        assertEquals("5", c["statementDay"])
        assertEquals("28", c["dueDay"])
        assertEquals("500000", c["creditLimit"])
        assertEquals("3", c["payFromId"])
        // A plain account never carries card fields, even if they were typed before the kind changed.
        val p = accountCells(account(type = "CASH", statement = "5", due = "28", limit = "5000", payFrom = 3))
        assertNull(p["statementDay"])
        assertNull(p["payFromId"])
        assertNull(p["creditLimit"])
        assertEquals("0", p["openingBalance"])
        assertEquals(15, AccountBridge.cellCount())
    }

    @Test
    fun peopleAreCheckedAndTheOpeningIsSigned() {
        KassaCore.installDefaults()
        assertEquals(emptyList(), PersonBridge.problems("Sam", true, "", listOf("Alex"), "USD"))
        assertEquals(listOf("NAME"), PersonBridge.problems("", true, "", emptyList(), "USD").map { it.field })
        assertEquals(listOf("AMOUNT"), PersonBridge.problems("Sam", true, "x", emptyList(), "USD").map { it.field })
        assertEquals(1250L, PersonBridge.opening("Sam", true, "12.50"))
        assertEquals(-1250L, PersonBridge.opening("Sam", false, "12.50"))
        assertEquals("Sam Lee", PersonBridge.cleanName(" Sam  Lee "))
        assertEquals("Starting balance", PersonBridge.startingNote())
    }

    // ---------- first run ----------

    @Test
    fun theStarterCategoriesBecomeCellsOfTheCategoriesTable() {
        val names = StartBridge.starters().filter { it.on }.map { it.name }
        assertEquals(14, names.size)
        assertEquals(14, StartBridge.categoryCount(names))
        val columns = BackupRows.columns("categories")
        fun row(i: Int) = columns.indices.associate { columns[it] to StartBridge.categoryCell(names, i, it, 5) }
        val bills = row(0)
        assertEquals("Bills", bills["name"])
        assertEquals("EXPENSE", bills["kind"])
        assertEquals("symbol:bills", bills["icon"])
        assertEquals("0", bills["countsTowardDaily"])
        assertEquals("0", bills["hidden"])
        assertEquals("5", bills["sortOrder"])
        assertNull(bills["id"])
        assertNull(bills["parentId"])
        assertNull(bills["externalId"])
        assertEquals("1", row(1)["countsTowardDaily"])
        assertEquals("6", row(1)["sortOrder"])
        val last = row(13)
        assertEquals("Salary", last["name"])
        assertEquals("INCOME", last["kind"])
        assertEquals("symbol:payments", last["icon"])
        assertNull(StartBridge.categoryCell(names, 14, columns.indexOf("name"), 5))
    }

    @Test
    fun theCurrencyListHasSamplesAndTheRegionDecidesTheStart() {
        KassaCore.installDefaults()
        val list = StartBridge.currencies()
        assertEquals(35, list.size)
        assertEquals("$1,234.50", list.first { it.code == "USD" }.sample)
        assertEquals("₹1,234.50", list.first { it.code == "INR" }.sample)
        assertEquals(list.map { it.name }.sortedBy { it.lowercase() }, list.map { it.name })
        assertEquals("INR", StartBridge.regionCurrency("IN", "INR"))
        assertEquals("", StartBridge.regionCurrency("", "USD"))
        assertEquals("", StartBridge.regionCurrency("JP", "JPY"))
    }

    @Test
    fun fieldsFilterWhatIsTyped() {
        KassaCore.installDefaults()
        assertEquals("1250.5", FieldBridge.typedAmount("1,250.5"))
        assertEquals("-12.34", FieldBridge.typedBalance("-12.345"))
        assertEquals("48", FieldBridge.typedDigits("4a8b99", 2))
        assertEquals("x".repeat(40), FieldBridge.typedName("x".repeat(50)))
    }

    // ---------- pickers and editing, over a small ledger ----------

    private fun acct(id: Long, name: String, type: String, closed: Int? = null, last4: String? = null) =
        BackupAccount(id, name, type, null, 0, null, null, null, null, false, id.toInt(), null, last4, null, closed)

    private fun cat(id: Long, name: String, kind: String = "EXPENSE", parent: Long? = null, icon: String = "symbol:cart", hidden: Boolean = false) =
        BackupCategory(id, name, kind, parent, icon, 0x112233, true, hidden, id.toInt())

    private fun tx(id: Long, kind: String, from: Long?, to: Long? = null, cat: Long? = null, at: Long, person: Long? = null, share: Long? = null) =
        BackupTransaction(id, kind, 1000, from, to, cat, person, at, "n$id", share)

    private fun ledger(): LedgerView = LedgerView(
        BackupData(
            createdAt = 1, monthStartDay = 1, currency = "USD",
            accounts = listOf(
                acct(1, "Checking", "CHECKING", last4 = "4821"), acct(2, "Card", "CREDIT_CARD"), acct(3, "Old", "SAVINGS", closed = 20_000),
                acct(4, "Wallet", "CASH"), acct(5, "Brokerage", "INVESTMENT"),
            ),
            categories = listOf(
                cat(1, "Groceries"), cat(2, "Fruit", parent = 1), cat(3, "Dining", icon = "emoji:🍽️"), cat(4, "Salary", "INCOME"),
                cat(5, "Hidden", hidden = true),
            ),
            people = listOf(BackupPerson(1, "Sam", false)),
            loans = listOf(BackupLoanEntry(1, 1, 500, 5, "split", 13)),
            transactions = listOf(
                tx(10, "EXPENSE", 1, cat = 1, at = 1_000_000),
                tx(11, "INCOME", 1, cat = 4, at = 3_000_000),
                tx(12, "TRANSFER", 1, 4, at = 2_000_000),
                tx(13, "EXPENSE", 1, cat = 1, at = 1_500_000),     // split: loan entry names it
                tx(14, "TRANSFER", 1, 2, at = 900_000),            // a card payment
                tx(15, "BUY", 5, at = 800_000),
                tx(16, "PERSON_OUT", 1, at = 700_000, person = 1),
                tx(17, "EXPENSE", 1, cat = 1, at = 600_000, share = 300),
            ),
        ),
        null,
    )

    @Test
    fun theCategoryPickerListsTopCategoriesAToZAndSearches() {
        val v = ledger()
        assertEquals(listOf("Dining", "Groceries"), v.categoryChoices("EXPENSE", "").map { it.name })
        assertEquals(listOf("Salary"), v.categoryChoices("INCOME", " ").map { it.name })
        assertEquals(emptyList(), v.categoryChoices("TRANSFER", ""))
        val groceries = v.categoryChoices("EXPENSE", "").first { it.name == "Groceries" }
        assertTrue(groceries.hasSubcategories)
        assertEquals("symbol", groceries.iconKind)
        assertEquals("cart", groceries.iconValue)
        val dining = v.categoryChoices("EXPENSE", "").first { it.name == "Dining" }
        assertFalse(dining.hasSubcategories)
        assertEquals("emoji", dining.iconKind)
        assertEquals("🍽️", dining.iconValue)
        val hits = v.categoryChoices("EXPENSE", "fru")
        assertEquals(listOf("Fruit"), hits.map { it.name })
        assertEquals("Groceries", hits.single().parentName, "a subcategory wears its parent's icon and says who it is under")
        assertEquals("cart", hits.single().iconValue)
        assertEquals(listOf("Fruit"), v.subcategoryChoices(1).map { it.name })
        assertEquals(emptyList(), v.categoryChoices("EXPENSE", "hidden"))
        assertEquals("Fruit", v.categoryChoice(2)?.name)
        assertNull(v.categoryChoice(99))
    }

    @Test
    fun theAccountPickerLeavesOutClosedAccountsAndGroupsByKind() {
        val v = ledger()
        val p = v.accountPick("EXPENSE", "ACCOUNT", 0, "ALL", "", 3)
        assertEquals(listOf("CASH_AND_BANK", "INVESTMENTS", "CREDIT_CARDS"), p.groups.map { it.key })
        assertEquals(listOf("Checking", "Wallet"), p.groups[0].accounts.map { it.name })
        assertEquals("4821", p.groups[0].accounts[0].last4)
        assertEquals(listOf("ALL", "CASH_AND_BANK", "CARDS", "INVESTMENTS"), p.filters)
        assertFalse(p.noneOpen)
        assertFalse(p.groups.flatMap { it.accounts }.any { it.name == "Old" })
        // The Recent row lists what the newest entries used, once each; nothing is guessed.
        assertEquals(listOf("Checking", "Brokerage"), p.recent.map { it.name })
    }

    @Test
    fun aTransferCantComeFromACardAndNeverGoesToItsSource() {
        val v = ledger()
        val from = v.accountPick("TRANSFER", "ACCOUNT", 0, "ALL", "", 3).groups.flatMap { it.accounts }.map { it.name }
        assertEquals(listOf("Checking", "Wallet", "Brokerage"), from)
        val to = v.accountPick("TRANSFER", "TO", 1, "ALL", "", 3).groups.flatMap { it.accounts }.map { it.name }
        assertEquals(listOf("Wallet", "Brokerage", "Card"), to)
    }

    @Test
    fun theKindChipAndTheTypedTextNarrowThePicker() {
        val v = ledger()
        assertEquals(listOf("Card"), v.accountPick("EXPENSE", "ACCOUNT", 0, "CARDS", "", 3).groups.flatMap { it.accounts }.map { it.name })
        assertEquals(listOf("Checking"), v.accountPick("EXPENSE", "ACCOUNT", 0, "ALL", "4821", 3).groups.flatMap { it.accounts }.map { it.name })
        assertEquals(emptyList(), v.accountPick("EXPENSE", "ACCOUNT", 0, "ALL", "zzz", 3).groups)
        assertEquals(emptyList(), v.accountPick("EXPENSE", "ACCOUNT", 0, "CARDS", "", 3).recent, "a chip hides Recent")
        assertEquals(emptyList(), v.accountPick("EXPENSE", "ACCOUNT", 0, "ALL", "c", 3).recent, "typing hides Recent")
    }

    @Test
    fun anEmptyLedgerOffersNoAccountAndSaysSo() {
        val v = LedgerView(BackupData(createdAt = 1, monthStartDay = 1, currency = "USD"), null)
        val p = v.accountPick("EXPENSE", "ACCOUNT", 0, "ALL", "", 3)
        assertTrue(p.noneOpen)
        assertEquals(listOf("ALL"), p.filters)
        assertEquals(emptyList(), v.categoryChoices("EXPENSE", ""))
    }

    @Test
    fun onlyPlainExpensesIncomeAndTransfersAreEditable() {
        KassaCore.installDefaults()
        val v = ledger()
        val rows = v.activity(1970, 1, false, 0, "UTC").days.flatMap { it.entries }.associateBy { it.id }
        assertEquals(setOf(10L, 11L, 12L, 14L), rows.filterValues { it.editable }.keys, "a card payment is a transfer, edited on the entry page as on Android")
        assertFalse(rows.getValue(13).editable, "a split")
        assertFalse(rows.getValue(15).editable, "a buy")
        assertFalse(rows.getValue(16).editable, "a person's payment")
        assertTrue(rows.getValue(16).personPayment, "it opens the person payment sheet instead")
        assertFalse(rows.getValue(17).editable, "a share on the entry")
        for (id in listOf(10L, 11L, 12L, 14L)) assertEquals(id, v.entryEdit(id)?.id)
        for (id in listOf(13L, 15L, 16L, 17L, 99L)) assertNull(v.entryEdit(id), id.toString())
    }

    @Test
    fun anEntryComesBackAsTheStoredValuesForTheEditPage() {
        val v = ledger()
        val e = v.entryEdit(12)!!
        assertEquals(EntryEditData(12, "TRANSFER", 1000, 1, 4, 0, 2_000_000, "n12"), e)
        val x = v.entryEdit(10)!!
        assertEquals(EntryEditData(10, "EXPENSE", 1000, 1, 0, 1, 1_000_000, "n10"), x)
    }

    @Test
    fun accountAndPersonNamesIncludeArchivedAndClosedOnes() {
        val v = ledger()
        assertEquals(listOf("Checking", "Card", "Old", "Wallet", "Brokerage"), v.accountNames())
        assertEquals(listOf("Sam"), v.personNames())
        assertEquals(6, v.nextAccountSortOrder())
        assertEquals(listOf("Checking", "Wallet"), v.payFromChoices().map { it.name })
        assertEquals(0, LedgerView(BackupData(createdAt = 1, monthStartDay = 1), null).nextAccountSortOrder())
    }

    @Test
    fun theDeleteQuestionNamesTheAmountAsMoneyFormatWritesIt() {
        KassaCore.installDefaults()
        val v = ledger()
        assertEquals("$10.00", v.entryAmountText(10, false))
        assertEquals(Money.MASK, v.entryAmountText(10, true))
        assertEquals("", v.entryAmountText(99, false))
        assertEquals("$", v.currencySymbol())
        assertEquals("$1.00", Money.format(100), "Money is put back")
    }

    @Test
    fun theCardPayFromPickerOffersOnlyCheckingSavingsAndCash() {
        val v = ledger()
        val p = v.payFromPick("")
        assertEquals(listOf("Checking", "Wallet"), p.groups.flatMap { it.accounts }.map { it.name })
        assertEquals(emptyList(), p.recent)
        assertEquals(listOf("Wallet"), v.payFromPick("wal").groups.flatMap { it.accounts }.map { it.name })
        assertTrue(LedgerView(BackupData(createdAt = 1, monthStartDay = 1), null).payFromPick("").noneOpen)
    }

    @Test
    fun theStoresOwnEditableCheckMatchesTheRule() {
        assertTrue(EntryBridge.isEditable("EXPENSE", "", false, false, false))
        assertTrue(EntryBridge.isEditable("TRANSFER", "SAVINGS", false, false, false))
        assertTrue(EntryBridge.isEditable("TRANSFER", "CREDIT_CARD", false, false, false))
        assertFalse(EntryBridge.isEditable("EXPENSE", "", true, false, false))
        assertFalse(EntryBridge.isEditable("EXPENSE", "", false, true, false))
        assertFalse(EntryBridge.isEditable("EXPENSE", "", false, false, true))
        assertFalse(EntryBridge.isEditable("BUY", "", false, false, false))
        assertFalse(EntryBridge.isEditable("nonsense", "", false, false, false))
    }

    @Test
    fun aListedCurrencyIsRecognisedInAnyCase() {
        assertEquals("EUR", StartBridge.listed("eur"))
        assertEquals("", StartBridge.listed("JPY"))
        assertEquals("", StartBridge.listed(""))
    }
}
