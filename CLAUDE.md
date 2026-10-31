# Kassa: notes for working on this repo

Offline personal finance app for Android. Kotlin, Jetpack Compose, Room on SQLCipher.

## Rules that matter
- **No network, ever.** The manifest removes `INTERNET`; CI fails if it appears. Don't add libraries that need it.
- **Money is `Long` cents.** Never `Double` for stored or summed amounts. Shares are millionths (`microShares`).
- **No currency is written anywhere.** Each phone picks one currency (Settings > Currency, or on first run); amounts
  are minor units and are never converted. Format with `Money.format`, put numbers in fields with `Money.toInput`,
  and use `Money.symbol` for a bare symbol. Never type `$` in UI code.
- **Nothing posts by itself.** Bills, card payments and loan payments wait on the Notifications page
  (`ui/today/Notifications.kt`, linked from Overview only when something waits) from their due day, never before, and
  stay while overdue; so do investment transfers and suggested bills. They wait until the user acts or clears them.
  Clearing never adds or pays anything. Phone notifications only tell; they never pay or add.
- **Nothing guesses an account.** A new entry, payment or card payment starts with no account unless the user set one
  (a card's own "pay from"). Nothing is preselected from a category or merchant.
- **Lists of names are A to Z** (`Names.order`, the phone's language): categories, subcategories, accounts, people,
  and the pickers on Add. Entries stay by date; spending breakdowns stay largest first.
- **Every input is bounded.** Limits live in `domain/InputRules` (names 40, categories 30, notes 120, amounts
  99,999,999.99). Forms filter as you type (`NameField`, `AmountField`, `DigitsField` in `ui/common/Fields.kt`) and
  show the problem under the field; repositories check the same rules again and throw `IllegalArgumentException`
  with a message, which `LedgerViewModel.change` shows. Any text that shows a name or note sets `maxLines` and
  `TextOverflow.Ellipsis`.
- **User data never goes in the repo.** Tests use synthetic data shaped like the Money Manager schema.

## Built for public release
Kassa is headed to the Play Store, so code is written for strangers' phones, not one Fold:
- No personal assumptions: nothing tied to the owner's banks, cards, Money Manager setup or Splitwise habits.
  Defaults must make sense for someone starting fresh.
- Locale-aware: dates, weekdays and numbers come from the phone's locale (never a hardcoded `Locale.US`);
  currencies cover what real users need. New UI copy should be easy to move into `strings.xml` later.
- Accessible: content descriptions on icon buttons, layouts that survive large font sizes, 48dp touch targets.
- Works on ordinary phones and tablets, not only the Fold; light and dark.
- Privacy is the product: still no network, no analytics, no crash upload. Crash reports stay on the phone
  and the user chooses to share them.
- Third-party libraries need licenses compatible with a public app, and their notices shown in the app.
- Android comes first. A native iOS app (SwiftUI) is planned for later. One set of rules for every app: `domain/`
  and `importer/` become a Kotlin Multiplatform library the iOS app calls, with native screens on each platform (no
  shared UI). Keep them free of Android APIs, and prefer `kotlinx-datetime` over `java.time` in new rule code.
- Screen text moves into `strings.xml` in one pass right before the Play Store launch; until then, keep new copy plain
  and easy to move.

## Layout
- `domain/`: plain Kotlin rules (money, card cycles, periods, splits, insights, input rules, account form, shares).
  Fast JVM tests.
- `importer/`: imports from other apps, plain Kotlin so the same rules can serve an iOS app later: Money Manager `.mmbak`
  (`MoneyManagerImporter`) and Cashew backups (`CashewImporter`: scan, then a plan from the user's answers about account
  kinds, loans and other currencies). Each reads through a source interface that the Android side implements over
  SQLite (`data/mm`, `data/cashew`); `data/PickedFile` works out which kind of file was picked from its contents. Every
  plan checks each account's balance against the source. Names and notes are cleaned and capped on the way in. JVM tests,
  plus a device test on a real SQLite file and a CI walkthrough of the Cashew screens (`.github/scripts/demo-shots.sh`).
- `app/data/db`: Room entities, DAOs, `KassaDatabase` (`DATABASE_VERSION`), `Migrations.kt`.
- `app/data/repo`: one repository per thing (entries, accounts, categories, people, bills, investments, import). They
  are the only code that writes to the database, and each validates before writing.
- `app/data/backup`: the `.kassa` file format (`BackupFile`), its JSON (`BackupContents`), and `BackupRepository`.
- `app/ui`: `MainActivity` (theme, lock) → `AppRoot` → `Shell` (tabs) with `Routes.kt` mapping tabs and pages to
  screens. `AppController` holds navigation and the actions screens call; `LedgerViewModel` exposes one `Ledger`
  (everything screens read) and runs changes. Phone preferences are read through `LocalSettings`.
- Big screens keep their state in a plain state holder next to them (`EntryState` for the entry page,
  `AccountForm` for the account forms) so the composables only lay things out.

## Database changes
Bump `DATABASE_VERSION` and add a `Migration` to `ALL_MIGRATIONS`. Columns must match the entity exactly
(type, nullability, indexes named `index_<table>_<column>`). CI installs the last published release on an emulator
first and upgrades over it, so a bad migration fails the build instead of reaching the phone.

## Build and release
- `./gradlew :domain:test :importer:test :app:testDebugUnitTest` runs the JVM tests (rules, importer, backup format).
- `app/src/androidTest` holds device tests: upgrades from every database version (`app/schemas`; 1 to 3 were rebuilt
  from 4.json by undoing each migration), entries with splits, and backup round trips. CI runs them on the emulator
  after the smoke test. The debug build is `app.kassa.money.debug`, so it installs beside the real app.
- Every build on `main` runs tests, builds a release APK signed with the Kassa key (four repo secrets), checks the
  certificate and the no-network rule, installs it over the last release on an emulator, then publishes `v0.2.<commit count>`
  as a GitHub release. Only the newest commit on `main` is published.
- Pushes from the Claude GitHub App sometimes don't trigger a run; start one with a `workflow_dispatch`.
- Build logs aren't readable through the API from Claude sessions, so failures upload logs to a draft release
  `diag-<run>`, and emulator screenshots/logcat go to `ci-<run>`.

## Navigation
`ui/Nav.kt` holds the tabs and a back stack of `Page`s in `NavViewModel`, so folding the phone keeps your place.
Anything you browse or fill in at length is a page with a back button (entry, card, account, person, category, week).
Tabs: Overview, Activity, Insights, Accounts, Settings, with Add as a floating button above the bar. Overview, top to bottom: Notifications (when anything waits), Spending/Income with three top-category tiles, Upcoming (three rows; five unfolded), and up to six pinned account tiles, all the same height. It fits the screen without scrolling (Upcoming drops rows first) and scrolls only when even one row won't fit; unfolded with nothing beside it, the tiles sit in a second column (`ui/today/AccountTiles.kt`; Settings › Home screen also picks the tab Kassa opens on). Over pages Add is hidden, except on card, account and category pages, where it starts the entry with that account or category picked (`AppController.presetFor`); the rail's Add does the same for the page beside the list. Cards live in Accounts. Settings is its own tab. Quick actions stay bottom sheets (pay a card, holdings, add an account). On the cover screen a page replaces
the tabs; at 720dp+ it opens beside the list it came from, except Add/Edit, which always takes the full screen. Anything that removes or replaces data asks first
(`ConfirmDialog`).

## One component per job
Every screen uses the same piece for the same job, so a change in one place looks the same everywhere. Before
writing a new control, find the existing one; if it doesn't fit, change it rather than adding a second version.
- Dates: `MonthCalendar` (the entry page's date panel) and `DateRow` (Today, Yesterday, Other day; opens the same
  calendar). Never Material's `DatePicker`.
- Amounts `AmountField`, names `NameField`, short numbers `DigitsField` (`ui/common/Fields.kt`); the entry and bill
  pages use `NumberPad`.
- Pick-one choices `Chip`; one-tap answers `QuickButton`; anything that removes data `ConfirmDialog`;
  short messages `MessageHost`; Add `AddButton`.

## One page per thing
Every category opens `CategoryPage`, every entry opens the entry page (`TransactionRow` onClick), every card `CardPage`,
every day `DayPage`, every store `InsightStorePage`, and "this week"/"this month" opens `SpendingPage`. Category lists
everywhere use `CategoryLines` in `ui/common/Breakdown.kt`. Reuse these; don't build a second version. "This week" starts on
the phone's first day of the week (`Ledger.weekStart`).

## Repeating charges
`domain/Repeats.kt` finds what repeats in about two years of entries (`Ledger.repeats`): notes match loosely
(`Repeats.key` drops trailing months, years, numbers, #ids), one store's different charges split by amount, and
weekly to yearly cadences are recognised. Bills match entries the same way. Only a monthly expense with the same
amount three times is suggested as a bill; ones that vary are added by the user.

## Wording
Cards follow Money Manager: **Balance payable** is the unpaid last statement, **Outstanding** is charges since the
statement date, **Total balance** is both. A statement covers everything up to the day before its statement date; the
statement date itself starts the next cycle. Card figures are never clamped: paying past zero shows a green credit. Accounts show Assets, Liabilities and Net worth; people are Receivable or
Payable.

## Entries
The entry page edits only expenses, income and transfers. Buys and sales open the trade sheet (their shares and
price live in `trades`; trades from before database v7 have none and only their date can change), payments to or
from a person open the payment sheet, and any entry that belongs to a loan payment (looked up in `loan_payments`
first, whatever its kind) opens the loan payment sheet. `AppController.openEntry` routes each kind; never send another kind to the entry page.
Nothing guesses an account: a new entry starts with none and you pick it. Splits live on the entry page (Split row):
several people, who paid, equal or exact shares; a person's "Split a transaction" opens that same page.

## Loans
A loan account (`AccountType.LOAN`) can carry terms; adding them upgrades it in place and keeps its entries and balance. A new
loan comes from Add account (pick "Loan or mortgage"), already partway or starting now; "Add terms" on a loan's page does the
same for an existing one. Three tables: `loan_terms` (one row per loan: type, Islamic `profit` label flag, rate, payment,
term, original amount, start, next payment date, due day, escrow, fee, early repayment charge, default interest category,
pay from), `loan_term_changes` (a new rate, payment, term or type from a date; earlier payments keep their split), and
`loan_payments` (the link below). Rates are Longs in 1/10000 of a percent (6.125% is 61,250), dates are epoch-day Ints, and
`payment` is principal plus interest with `escrow` on top. `LoanRepository` is the only writer.
- **A payment is stored as ordinary entries** so balances, Activity and Insights keep working: the principal is a TRANSFER from
  the pay-from account to the loan, the interest is an EXPENSE in the interest category, and escrow plus any early repayment
  charge is a second EXPENSE. A `loan_payments` row ties them together (and keeps the escrow, charge, whether the interest
  was edited, Kassa's estimate, and whether it covered the scheduled payment, which moves `nextPaymentDay` on a month).
  Edit and delete act on the whole payment; never edit one of its entries on its own.
- **Interest category:** "Loan interest" is created with the first loan if it isn't there. A loan's terms may name any category
  instead (null means Loan interest), and so may a single payment. Kassa never makes a subcategory per loan.
- **The maths is `domain/Loans.kt`** (`LoanEngine`: split suggestion, forecast, payoff, rate changes, flat-to-reducing rate),
  `LoanDates.kt` (epoch-day calendar), and the state holders `LoanForm` (setup) and `LoanPaymentForm` (payment sheet), all plain
  Kotlin with JVM tests. Interest rounds half-up to the cent, principal is the remainder, never negative or above the balance,
  and the last payment clears it. Nothing posts by itself; a due loan payment waits on Notifications from `nextPaymentDay`.
- **Escrow category:** one shared "Escrow" expense category is made the first time a loan with escrow is saved (like Loan
  interest). The escrow (and early repayment charge) entry of a payment goes there unless the payment sheet names another; a
  loan starts its next payment from the category its last escrow entry went to (`LoansState.escrowCategory`), since a stored
  per-loan override would need a `loan_terms` column. A loan's payment entries carry `LoanNotes`, which also keeps a steady
  escrow or flat-rate interest out of the suggested bills.
- **Loan page** (`AccountPage` for a loan with terms; `ui/loans/LoanPage.kt`): Outstanding balance (ink, no minus), a progress bar
  only when the original amount is known, then Payment (with its due date), Paid off, Interest (Profit) paid so far (the linked
  interest entries) and remaining, the Payment schedule (next 6 rows, "Show full schedule"), Terms with each dated change (tap to
  remove), and Record a payment, Change terms, Edit details, Correct balance. Figures come from `LoanSummary.of`, so they use the
  terms in effect on each date. Activity rows of a payment carry a small tag (principal, interest, escrow, charge) and open the
  payment sheet (`LoansState.tagFor`); never split such an entry (the person page's list leaves them out).
- **Change terms** (`Page.ChangeTerms`, `LoanChangeForm`): Rate, Payment, Interest type or Refinanced, from a date (future allowed,
  never before the loan's start). A rate change asks what the lender kept and previews the new payment or payoff from
  `LoanEngine.rateChange`. It saves one `loan_term_changes` row (`LoanRepository.addChange`); payments before it keep their split
  because the engine reads each date's terms. A change that gives a term always saves the payment that goes with it.
- **Edit details** (`LoanDetailsForm`, `LoanDetailsSheet`): name, due day (moves the next payment date), pay from, escrow, fee, early
  repayment charge, interest category, original amount and start. It reuses the setup form's pieces (`PayFromPicker`,
  `DueDayField`, `CostFields`, `InterestCategoryRow`, `ProgressFields`).
- **Balance correction** (`domain/BalanceCorrection.kt`, `EntryRepository.correctBalance`, `BalanceCorrectionSheet`): a dated entry
  of kind `ADJUST_IN` (balance up) or `ADJUST_OUT` (down), amount positive. It is stored as text, so no schema change, moves the
  balance (the SQL balance sums and `effectOn` know it, so the loan engine and every payment check see it), backs up like any
  entry, and is in no spending, income, budget or daily figure because those count only `EXPENSE` and `INCOME`: keep it that way,
  and give a new money figure the same explicit kind filter. Activity shows it as "Balance correction" with an arrow and the size
  (in what the account shows: owed, for a debt), never a minus; it opens the correction sheet to edit or delete. The sheet asks for
  the balance the lender shows, shows Kassa's balance and the difference, and leaves earlier entries untouched. Loan pages offer it
  first; any account can.
- Screens are in `ui/loans/` (setup and details fields, payment sheet, loan page, change terms); `LoanForm`, `LoanPaymentForm`,
  `LoanChangeForm` and `LoanDetailsForm` hold the state, all plain Kotlin with JVM tests.

## Payments due and notifications
`domain/DueNotices.kt` decides everything (epoch days, minutes of day, no Android): `DueItems.build` lists what is due (a card
with balance payable on its due date, a loan on `nextPaymentDay` unless a payment on or after it is recorded, an unhandled bill
with no matching entry), `DueNotices` gives the day a notification goes out (due day minus 0 to 14, a due day of 31 clamped
to the month's last), what rings now (3 hour grace, never twice for one key), the next alarm, and `text` (the one function
that words a notification, with `showAmount` for a future Hide balances switch). The Notifications page uses the same list,
filtered to due today or overdue. Settings keeps one `DueRule` per kind (cards and loans on, bills off; due day, 1 or 3 days
before, or 0 to 14; time, default 9:00); the daily reminder keeps its own switch and time.
`data/jobs/DueNotifier.kt` reads the database, posts what rings (one notification per item, one summary when two or more),
and sets ONE alarm (request code 103, exact when allowed, else allow-while-idle) for the earliest next one, or a daily look
at 00:05 so a card's new statement is noticed. `refresh` is called at boot, update, time and zone change, app start, Settings
changes, `LedgerViewModel.change` and restore. Posted keys live in the private `kassa_due` prefs. A tap opens the card page,
the loan's page with its payment sheet, or the bill (`MainActivity` `ACTION_OPEN_DUE` to `AppController.openDue`). Android's
permission is asked once, with an explanation, after the first card with a due day or loan with terms is saved
(`LedgerViewModel.askNotifications`); if refused, Settings > Notifications shows "Notifications are off for Kassa".

## Style
Plain, specific UI copy in sentence case. Let figures speak: labelled numbers, not sentences explaining them. Color is only for categories and state; primary actions are ink on ground.
Money has one color rule (`favourColor`, `balanceColor` in `ui/common/Components.kt`): **green** when it's in your favour
(income, profit, someone owes you, a card in credit), **orange** when it's against you beyond ordinary spending (an
investment loss, you owe someone, over budget, a card payment due or overdue, a bank or cash balance below zero, net
worth below zero), **ink** for everything else (expenses, transfers, balances, totals). A minus sign is always orange, so
one only appears when it's against you: cards and loans show what you owe with no minus (`cardMoney`,
`cardTint`, a credit as a green +), payments to or from a person show no sign, and changes over time use ↑ and ↓.
Of card figures only payable is orange (`payableTint`: Balance payable, Payable, due lines); outstanding, total
balance and Liabilities stay ink. Categories may share a color (`CategoryColors`): Overview's top categories are tinted
tiles sized by share, with icons, so a shared color never reads as one; the picker marks colors in use and names who
shares the picked one; a new category gets an unused color while one is free.
Every screen works on the Fold's cover screen and switches to two panes at 600dp+ (720dp for list-detail).
