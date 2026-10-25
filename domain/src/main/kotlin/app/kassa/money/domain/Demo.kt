package app.kassa.money.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** A made-up household for screenshots and walkthroughs. Same seed, same data; nothing here is anyone's real money. */
data class DemoData(
    val accounts: List<Account>,
    val categories: List<Category>,
    val transactions: List<Transaction>,
    val budgets: List<Budget>,
    val people: List<Person>,
    val loans: List<LoanEntry>,
    val bills: List<Recurring>,
    val holdings: List<Holding>,
)

/**
 * Builds about four months of believable entries ending [today]: a salary every two weeks, rent, bills that vary,
 * groceries, dining, a credit card paid each month, savings, a brokerage holding and two people with small balances.
 */
object Demo {
    private const val CHECKING = 1L
    private const val SAVINGS = 2L
    private const val CARD = 3L
    private const val CASH = 4L
    private const val BROKERAGE = 5L

    fun data(today: LocalDate, zone: ZoneId, days: Long = 120): DemoData {
        val rnd = Random(7)
        val start = today.minusDays(days)

        val accounts = listOf(
            Account(CHECKING, "Everyday checking", AccountType.CHECKING, openingBalance = 320_000, sortOrder = 0),
            Account(SAVINGS, "Savings", AccountType.SAVINGS, openingBalance = 850_000, sortOrder = 1),
            Account(CARD, "Travel card", AccountType.CREDIT_CARD, statementDay = 15, dueDay = 10, payFromId = CHECKING, creditLimit = 800_000, sortOrder = 2),
            Account(CASH, "Wallet", AccountType.CASH, openingBalance = 6_000, sortOrder = 3),
            Account(BROKERAGE, "Brokerage", AccountType.INVESTMENT, sortOrder = 4),
        )

        val cats = mutableListOf<Category>()
        var nextCat = 1L
        fun top(name: String, icon: String, kind: CategoryKind = CategoryKind.EXPENSE, fixed: Boolean = false, vararg subs: String): Long {
            val id = nextCat++
            val color = CategoryColors.palette[(id.toInt() - 1) % CategoryColors.palette.size]
            cats += Category(id, name, kind, icon = CategoryIcon.Symbol(icon), color = color, countsTowardDaily = !fixed)
            subs.forEach { s -> cats += Category(nextCat++, s, kind, parentId = id, icon = CategoryIcon.Symbol(icon), color = color, countsTowardDaily = !fixed) }
            return id
        }
        val bills = top("Bills", "bills", fixed = true, subs = arrayOf("Electricity", "Internet", "Phone"))
        val dining = top("Dining", "restaurant", subs = arrayOf("Coffee", "Dinner", "Lunch"))
        val groceries = top("Groceries", "cart")
        val home = top("Home", "home", fixed = true, subs = arrayOf("Rent"))
        val shopping = top("Shopping", "bag")
        val subscriptions = top("Subscriptions", "subscriptions", fixed = true)
        val transport = top("Transport", "bus", subs = arrayOf("Fuel", "Parking"))
        val entertainment = top("Entertainment", "movie")
        val health = top("Health", "medical")
        val salary = top("Salary", "payments", CategoryKind.INCOME)
        val interest = top("Interest", "savings", CategoryKind.INCOME)
        fun sub(parent: Long, name: String) = cats.first { it.parentId == parent && it.name == name }.id

        val txs = mutableListOf<Transaction>()
        fun at(d: LocalDate, hour: Int = 12) = d.atTime(hour, rnd.nextInt(0, 59)).atZone(zone).toInstant().toEpochMilli()
        fun spend(d: LocalDate, cents: Long, cat: Long, note: String, account: Long = CARD) =
            txs.add(Transaction(kind = TxKind.EXPENSE, amount = cents, accountId = account, categoryId = cat, epochMillis = at(d), note = note))
        fun between(lo: Int, hi: Int) = rnd.nextLong(lo * 100L, hi * 100L)

        var d = start
        while (!d.isAfter(today)) {
            // Fixed monthly charges.
            if (d.dayOfMonth == 1) spend(d, 185_000, sub(home, "Rent"), "Rent", CHECKING)
            if (d.dayOfMonth == 3) spend(d, between(90, 180), sub(bills, "Electricity"), "Electricity")
            if (d.dayOfMonth == 5) spend(d, 1_549, subscriptions, "Streaming")
            if (d.dayOfMonth == 15) spend(d, 4_500, sub(bills, "Phone"), "Phone plan")
            if (d.dayOfMonth == 20) spend(d, 6_000, sub(bills, "Internet"), "Internet")
            if (d.dayOfMonth == 22) spend(d, 1_099, subscriptions, "Music")
            // Pay the card and move some to savings.
            if (d.dayOfMonth == 10) txs += Transaction(kind = TxKind.TRANSFER, amount = between(1_100, 1_500), accountId = CHECKING, toAccountId = CARD, epochMillis = at(d, 9), note = "Card payment")
            if (d.dayOfMonth == 2) txs += Transaction(kind = TxKind.TRANSFER, amount = 30_000, accountId = CHECKING, toAccountId = SAVINGS, epochMillis = at(d, 9), note = "Savings")
            if (d.dayOfMonth == 28) txs += Transaction(kind = TxKind.INCOME, amount = between(10, 14), accountId = SAVINGS, categoryId = interest, epochMillis = at(d, 6), note = "Interest")
            // Paid every other Friday.
            if (d.dayOfWeek == DayOfWeek.FRIDAY && java.time.temporal.ChronoUnit.DAYS.between(start, d) / 7 % 2 == 0L)
                txs += Transaction(kind = TxKind.INCOME, amount = 248_000, accountId = CHECKING, categoryId = salary, epochMillis = at(d, 8), note = "Salary")
            // Everyday spending.
            if (d.dayOfWeek in setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)) spend(d, between(40, 140), groceries, if (rnd.nextBoolean()) "Corner market" else "Warehouse store")
            if (d.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)) spend(d, between(4, 7), sub(dining, "Coffee"), "Coffee shop")
            if (d.dayOfWeek == DayOfWeek.FRIDAY) spend(d, between(30, 80), sub(dining, "Dinner"), listOf("Noodle house", "Pizza place", "Taco stand")[rnd.nextInt(3)])
            if (d.dayOfWeek == DayOfWeek.WEDNESDAY) spend(d, between(10, 18), sub(dining, "Lunch"), "Deli", CASH)
            if (d.dayOfWeek == DayOfWeek.SUNDAY) spend(d, between(35, 55), sub(transport, "Fuel"), "Gas station")
            if (d.dayOfMonth % 9 == 0) spend(d, between(20, 120), shopping, listOf("Home goods", "Bookshop", "Hardware store")[rnd.nextInt(3)])
            if (d.dayOfMonth % 13 == 0) spend(d, between(12, 40), entertainment, "Cinema")
            if (d.dayOfMonth == 18) spend(d, between(5, 15), sub(transport, "Parking"), "Parking", CASH)
            d = d.plusDays(1)
        }
        spend(today.minusDays(40), 6_500, health, "Pharmacy")

        val budgets = listOf(groceries to 65_000L, dining to 30_000L, shopping to 25_000L, transport to 20_000L, entertainment to 12_000L)
            .map { (c, a) -> Budget(c, 0, a) }
        val people = listOf(Person(1, "Sam"), Person(2, "Alex"))
        val loans = listOf(
            LoanEntry(1, 1, 2_500, at(today.minusDays(6)), "Dinner, Sam's share"),
            LoanEntry(2, 2, -4_000, at(today.minusDays(12)), "Concert tickets"),
        )
        val recurring = listOf(
            Recurring(1, "Rent", sub(home, "Rent"), CHECKING, 1, fixedAmount = 185_000),
            Recurring(2, "Electricity", sub(bills, "Electricity"), CARD, 3, fixedAmount = null),
        )
        val holdings = listOf(Holding(1, BROKERAGE, "VTI", 12_000_000, 28_500, at(today), avgCost = 24_000))

        return DemoData(accounts, cats, txs.sortedBy { it.epochMillis }.mapIndexed { i, t -> t.copy(id = i + 1L) }, budgets, people, loans, recurring, holdings)
    }
}
