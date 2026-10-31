package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.Account
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.Person
import app.kassa.money.domain.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** People, the amounts they owe or are owed ([loans]), and the payments that settle them. */
data class PeopleState(
    val people: List<Person>,
    val loans: List<LoanEntry>,
    val payments: List<Transaction>,
)

private const val STARTING_NOTE = "Starting balance"

/** People you split costs with or lend to. */
class PeopleRepository(private val db: KassaDatabase) {

    val state: Flow<PeopleState> = combine(
        db.people().observeAll(),
        db.loans().observeAll(),
        db.transactions().observeWithPeople(),
    ) { people, loans, payments -> PeopleState(people.map { it.toDomain() }, loans.map { it.toDomain() }, payments.map { it.toDomain() }) }

    /** Adds a person, optionally with money already between you ([opening]: positive, they owe you). */
    suspend fun add(name: String, opening: Long = 0): Long = db.withTransaction {
        checkName(name, exceptId = null)
        InputRules.balanceProblem(opening)?.let { throw IllegalArgumentException(it) }
        val id = db.people().insert(Person(name = InputRules.clean(name)).toEntity())
        if (opening != 0L) db.loans().insertAll(listOf(
            LoanEntry(personId = id, amount = opening, epochMillis = System.currentTimeMillis(), note = STARTING_NOTE).toEntity()
        ))
        id
    }

    /**
     * Renames, archives or restores a person, and when [startingBalance] is given, replaces what was owed when
     * they were added (positive: they owe you). Splits and payments stay as they are.
     */
    suspend fun update(person: Person, startingBalance: Long? = null) = db.withTransaction {
        checkName(person.name, exceptId = person.id)
        db.people().update(person.copy(name = InputRules.clean(person.name)).toEntity())
        if (startingBalance != null) setStartingBalance(person.id, startingBalance)
    }

    /** Lines not tied to an entry make up the starting balance; they're replaced by one line (none when zero). */
    private suspend fun setStartingBalance(personId: Long, amount: Long) {
        InputRules.balanceProblem(amount)?.let { throw IllegalArgumentException(it) }
        val old = db.loans().startingFor(personId)
        if (old.sumOf { it.amount } == amount) return
        db.loans().deleteStarting(personId)
        if (amount != 0L) db.loans().insertAll(listOf(
            LoanEntry(personId = personId, amount = amount, epochMillis = old.firstOrNull()?.epochMillis ?: System.currentTimeMillis(), note = STARTING_NOTE).toEntity()
        ))
    }

    /**
     * Turns an account that stood in for money between you and someone (an account the user picks in Settings) into a
     * person carrying the same balance. The account is archived with its history, so past months don't change.
     */
    suspend fun fromAccount(account: Account, balance: Long, personName: String): Long = db.withTransaction {
        val id = add(personName, opening = 0)
        if (balance != 0L) db.loans().insertAll(listOf(
            LoanEntry(personId = id, amount = balance, epochMillis = System.currentTimeMillis(), note = "Balance from ${account.name}").toEntity()
        ))
        db.accounts().update(account.copy(archived = true).toEntity())
        id
    }

    private suspend fun checkName(name: String, exceptId: Long?) {
        val others = db.people().all().filter { it.id != exceptId }.map { it.name }
        InputRules.nameProblem(name, "person", taken = { n -> others.any { it.equals(n, ignoreCase = true) } })
            ?.let { throw IllegalArgumentException(it) }
    }
}
