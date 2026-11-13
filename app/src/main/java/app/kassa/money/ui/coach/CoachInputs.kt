package app.kassa.money.ui.coach

import app.kassa.money.domain.Coach
import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.CoachReport
import app.kassa.money.domain.Transaction
import app.kassa.money.ui.Ledger

/**
 * The input of the Coach rules, from what the [Ledger] already holds (accounts and their balances, categories, budgets, bills, loans, the budget
 * month's first day, today and the zone from `DayClock`, the phone's first weekday). [history] is the entries of about two years (the same window
 * `Repeats` reads), since the rules compare weeks and months and find what repeats. Nothing is cached: today comes from the ledger, which follows the clock.
 */
fun coachInput(l: Ledger, history: List<Transaction>): CoachInput = CoachInput(
    accounts = l.accounts.accounts,
    balances = l.accounts.balances,
    transactions = history,
    categories = l.categories,
    budgets = l.budgets,
    bills = l.bills,
    loanTerms = l.loans.terms,
    loanChanges = l.loans.changes,
    loanPayments = l.loans.payments,
    monthStartDay = l.monthStartDay,
    today = l.today,
    zone = l.zone,
    firstDay = l.firstDayOfWeek,
)

/** Runs every Coach rule on the ledger and its history. */
fun coachReport(l: Ledger, history: List<Transaction>): CoachReport = Coach.report(coachInput(l, history))
