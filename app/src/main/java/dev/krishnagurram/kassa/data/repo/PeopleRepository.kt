package dev.krishnagurram.kassa.data.repo

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toDomain
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.LoanEntry
import dev.krishnagurram.kassa.domain.Person
import dev.krishnagurram.kassa.domain.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** People, the amounts they owe or are owed ([loans]), and the payments that settle them. */
data class PeopleState(
    val people: List<Person>,
    val loans: List<LoanEntry>,
    val payments: List<Transaction>,
)

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
            LoanEntry(personId = id, amount = opening, epochMillis = System.currentTimeMillis(), note = "Starting balance").toEntity()
        ))
        id
    }

    /** Renames, archives or restores a person. Their history stays either way. */
    suspend fun update(person: Person) = db.withTransaction {
        checkName(person.name, exceptId = person.id)
        db.people().update(person.copy(name = InputRules.clean(person.name)).toEntity())
    }

    /**
     * Turns an account that stood in for money between you and someone (a Splitwise account, say) into a person
     * carrying the same balance. The account is archived with its history, so past months don't change.
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
