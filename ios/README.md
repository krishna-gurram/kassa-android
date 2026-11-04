# Kassa for iOS

SwiftUI app over the shared Kotlin code (`:domain` and `:importer`, exported as the static framework `KassaShared` by
`:shared`). The Xcode project is generated from `project.yml` with XcodeGen and is not checked in.

First milestone: a screen that formats a sample figure through the shared rules, and tests that the framework loads.

Stage B (first real screens, read-only): the app opens the encrypted store, builds `LedgerView` (in `:shared`, from the exported
`BackupData`) on one background queue, and lays out its flat figures. Tabs: Overview, Activity, Insights, Accounts, Settings; Accounts
and Activity are real, the rest say "Coming soon". An empty database shows the first-run screen: "Choose backup file" (file picker for
the `.kassa` type declared in `project.yml`, a password prompt for protected files) or "Start fresh". The eye hides every amount.
Figures, colours (`Tone`) and money text come from the shared Kotlin rules, so they equal Android's; Swift only lays out and words
them (`Wording.swift`, `Localizable.strings`, `Dates.swift` for the phone's date patterns). Not yet on iOS: Overview, Insights,
Settings, search and filters in Activity, archived accounts, restoring over existing data.

Stage C (write path): first run picks a currency (the phone's region currency when Kassa lists it, otherwise the list opens before
Start fresh or a backup without one) and the starter categories; Accounts has Add account (type first: cash and bank, cards, other,
people; fixed deposits are Android-only for now); every tab has a floating Add button that opens the entry page
(expense, income, transfer; number pad, category and account pickers, date row, note), and tapping an expense, income or transfer
row in Activity edits or deletes it. Other rows (loan payments, buys and sales, person payments, card payments, corrections) do nothing
yet. An unfinished new entry is kept per scene (`@SceneStorage`). All rules are :domain's (`EntryRules`, `AccountForm`, `PersonForm`,
`StarterCategories`, `AmountPad`, `CategoryPicking`), reached through `WriteBridge.kt` and `LedgerView`; `LedgerStore+Write.swift` runs each
change in one transaction; Swift only lays out.

Storage (stage A): `Kassa/Storage` holds an encrypted SQLCipher database (SPM package `SQLCipher.swift`, tables copied from Android's Room
schema 10, key in the Keychain) that restores from and exports to a `.kassa` backup; `StorageTests` cover it.

Stage D (Overview and Settings): Kassa opens on Overview: the Spending or Income hero of the current month (its period starts on the
backup's month start day, as Android's `Ledger.period`), the three largest categories with their icons, and the pinned accounts (open
accounts in Accounts order, then People, at most eight). All of it is `LedgerView.overview` in :shared (JVM-tested against the demo
backup). Settings has Hide balances (remembered in UserDefaults, applied to every figure; the eye is also in the top bar of Overview,
Activity and Accounts), Currency (relabels amounts without converting, asks first when there is data, as Android), Back up now (a `.kassa`
through `BackupFile`, optional password, saved with the system file exporter), Restore from a backup (file importer, password prompt, then a
"Replace everything?" question), and Third-party notices (SQLCipher's BSD-style notice and Apache 2.0, bundled from
`app/src/main/assets/licences`). Failures are worded by `Failures.swift` from `failure.*` strings (Android's `data/Failures.kt` sentences).

Stage E (Insights): the Insights tab shows one budget month (arrows move back, never past the current one): a grey spending hero with
"Up to <date>" and the same stretch of the month before (current month only), and By category (icon, name, share, amount, a bar against
the largest; largest first). Tapping a category opens its page with that month's entries, grouped by day with Activity's rows. All of it is
`LedgerView.insights` / `categoryPage` in :shared (counted spending, JVM-tested against the demo backup); hidden masks every amount and gives no
share or bar. Patterns tiles, the week view and budgets arrived in batch J (below).

Stage F (card, account and person pages): a tap on an Accounts row opens its page in place of the list, with a back button (`PageScreens.swift`, state in
`AppModel+Pages.swift`). A card with statement days shows Balance payable (with its due line), Total balance and Outstanding, a statement navigator and Pay card;
a plain account shows its balance, the month's In and Out, its entries and Close or Reopen (zero balance only, after a question); an investment account has its own page (stage H below; a loan too, stage G1). Pay card (`PaymentSheets.swift`) offers Balance payable, Total balance or another amount, picks the pay-from account (only the card's own
"pay from" is preselected) and a date, and saves a TRANSFER to the card as Android does. A person's page shows Receivable or Payable, its history and Record a
payment; payments open their own sheet to edit or delete. Rules are in `domain/Payments.kt`, reached through `LedgerView` and `PaymentBridge.kt`.

Stage G1 (loans, part one): Add account now offers Loan or mortgage (`LoanForm.swift`; the domain's `LoanForm` decides which fields a kind needs, the
problems and the live calculation, through `LoanBridge`). One transaction saves the account, its `loan_terms` row and the "Loan interest" category (and "Escrow"
with escrow) when missing (`LedgerStore+Loans.swift`). A loan with terms opens its own page (`LoanScreens.swift`): Outstanding (no minus) with a bar when the
original amount is known, Payment with its due date, Paid off, interest paid and remaining, the next six payments (Show full schedule), Terms with dated changes
(tap to remove) and the month's entries with payment tags; a loan without terms offers Add terms. Every figure is :domain's (`LoanSummary`, `LoanEngine`,
`CountedSpending`) through `LedgerView.loanPage`. Correct balance (account and loan pages, and a correction row in Activity) asks for what the bank or lender
shows, shows Kassa's figure and the difference, and saves ADJUST_IN/ADJUST_OUT (edit and delete too; a closed account is refused).

Stage G2 (loans, part two): the loan page offers Record a payment, Change terms and Edit details. Record a payment (`LoanPaymentSheet.swift`) is :domain's
`LoanPaymentForm` (suggested split, the part that follows the one typed, the interest-edited flag), pay-from through the account picker (empty unless the loan names one),
the shared category chooser (`LoanPickers.swift`) for interest, principal and escrow, and the one-time count-as-spending override; one transaction writes the entries and the
`loan_payments` row exactly like Android's `LoanRepository.recordPayment` (`LoanPaymentPlanner` in :domain holds the checks in the same order, `LedgerStore+LoanPayments.swift`
only runs the SQL). Opening a payment edits or deletes the whole payment, and any Activity row that belongs to one opens it (`EntryRowData.loanPaymentId`). Change terms
(`LoanChangeScreen.swift`) previews with `LoanEngine.rateChange` and saves one `loan_term_changes` row; Edit details (`LoanDetailsSheet.swift`, `LoanDetailsPlanner`) includes the
count-as-spending switch, which `CountedSpending` already carries into every spending figure. Tests: `LoanPaymentTests` (Swift), `LoanPaymentsTest` (JVM, demo backup);
the CI screenshots add `loan-payment-*`, `loan-change-*` and `loan-details-*`. Due notifications are still to come.

## CI

The `ios-app` job (macos-15) installs XcodeGen, runs `xcodegen generate` in `ios/`, then `xcodebuild test` on an iPhone 16
simulator. `ScreenshotTests` draws the Accounts, Activity, Overview, Insights, Settings, card page, pay-card, loan page, Add loan and correction screens (light and dark) to `ci-output/shots`, which a step uploads to
a draft release `ios-shots-<run>-<attempt>`; it never fails the build. A pre-build script phase in the Kassa target runs `./gradlew :shared:link<Debug|Release>Framework<Target>`
for the SDK being built. Logs go to `ci-output/ios-app.log`; on failure they are uploaded to a draft release
`diag-ios-app-<run>-<attempt>`.

## Build locally (on a Mac)

```
brew install xcodegen
cd ios
xcodegen generate
open Kassa.xcodeproj
```

JDK 17 must be on the path for the Gradle step. There is no network access in the app itself and none is to be added.

Stage H (investments): an INVESTMENT account's page (`InvestScreens.swift`, shown by `PageHost`) has Total value with the gain on what was put in, Cash,
the holdings (ticker, shares, price-based value, gain or loss: loss orange, profit green, no percent or direction while Hide balances is on), transfers
waiting to be explained (Answer or Clear; the Notifications page lists them too, stage I) and the cash moves by month. Sheets (`InvestSheets.swift`): a holding
(add shares you own, price today, what you paid while unknown, remove), Update prices (all saved together), Buy or sell, an existing trade (edit, or only
its date for a trade from before shares were kept, delete) and "Where did it go?". Rules are `domain/InvestRules.kt` (a plan of holdings changes, mirroring
Android's `InvestmentRepository` in the order of its checks) reached through `shared/.../InvestBridge.kt` and `LedgerView.investPage` and the sheet functions;
`LedgerStore+Invest.swift` only runs the SQL, one transaction per change (`afterInsertHook` for rollback tests). Activity rows of a buy or sale open the trade
sheet. Swift tests: `InvestTests`; JVM: `InvestRulesTest` and `InvestPagesTest` (demo backup: ABC 12.5 shares at 180.00 is 2,250.00, `holdingsValue`).

Stage I (due notices): Overview shows a tinted Notifications bar only when something waits (opens the page in place of Overview) and three Upcoming rows (card
payments and bills in the next 30 days; a card also while overdue). The Notifications page (`NoticesScreen.swift`) lists what is due today or overdue, waiting
investment transfers and suggested bills, each with its own choices: Pay (the pay-card or loan payment sheet), Add or Enter amount (a bill: `BillAmountSheet`),
Answer, Track as bill, and Clear. Clear never adds or pays anything and is remembered (`DueSettings.cleared`, UserDefaults; a bill's month is marked handled and
a transfer stops asking, in one transaction). Everything that decides is :domain: `DueItems.build`, `DueNotices` (`plan` returns the next 60 days as
`PlannedNotice`: key, time, generic text, kind, id), `BillRules`, `Repeats`, reached through `shared/.../DueBridge.kt` and `LedgerView.notices/upcoming/billsPage/
billSheet/billProblems/hasNotifiable/notificationPlan`. Bills (Settings > Bills: list, add, edit, delete; `BillScreens.swift`) are saved by `LedgerStore+Bills.swift`
in one transaction each; approving a bill writes its expense and marks the month handled together (`afterInsertHook` proves the rollback). Phone notifications
(`DueNotifier.swift`): `DueScheduler` turns the plan into `UNCalendarNotificationTrigger`s (identifier is the key, so repeating replaces; a notice whose time just
passed rings in 5 seconds; at most 60 plus the daily reminder, under the system's 64), with the generic text "Payment due" and no amount or name. It runs at app start,
when the app becomes active, after every read of the store (so after every change, restore and settings change). The permission is asked once, with an explanation,
after the first card with a due day or loan with terms is saved. Settings > Notifications (`NotificationSettingsScreen.swift`) has a rule per kind (cards and loans
on, bills off; 0 to 14 days before; time, default 9:00), the daily reminder, and "Notifications are off for Kassa" with a button to the phone's Settings.
`NotificationRouter` is the delegate: a tap opens the card page, the loan payment sheet, the bill, or a new entry (the reminder). iOS cannot wake Kassa at 00:05
as Android does, so a card's new statement is noticed the next time the app opens. Swift tests: `DueTests`; JVM: `DuePlanTest`, `BillRulesTest`, `DuePagesTest`
(demo backup). Nothing in batch I was compiled on the machine that wrote it; CI is the first compile.

Batch J (read-side extras, functional first; a visual redesign comes later): Insights has a Month/Week toggle. Month adds the six Patterns tiles
(Big purchases, Stores, Small purchases, Creeping up, Repeating, Busiest day; each opens its detail page) and the hero opens the month's spending page;
Week is the week view (hero against the same stretch of last week, a day strip, categories against their budget or last week, up and down most, due this
week, largest buys). The week starts on the phone's first weekday: Swift passes `Calendar.current.firstWeekday` and `WeekRules.firstDayOf` (:domain) maps it.
Pages open over the tabs from a stack in `AppModel` (`ReadRoute`: spending, day, store, detail; `ReadScreens.swift`, `ReadPages.swift`, `AppModel+Read.swift`);
a category always opens Insights' category page (Overview's top rows and the spending page's lines do, via `openCategory`). Activity has a search field
(note, category, account, person or exact amount over every month; `%`, `_` and backslash are text, `SearchText`), the Spending and Income totals as filters
(tap again for all), a month picker sheet (months after the current one off; `MonthPicking`), and a tap on a day header opens the day page. Settings >
Budgets lists every top-level expense category against its budget for the current budget month and edits one (an amount for every month, optionally one just
for this month; blank removes; month 0 is every month and yyyymm an override); `BudgetRules` decides, `LedgerStore+Budgets.swift` writes a whole save in one
transaction (a refused or failed save changes nothing). Over budget is orange in the shared row; while hidden there is no orange, no bar, no "over" or "left"
word. Everything is :domain / :shared (`ReadViews`, `ReadPages.kt`, `LedgerView.patterns/weekView/spendingPage/insightDetail/dayPage/storePage/budgetsPage/
budgetEdit/budgetPlan/activityFiltered/activitySearch`), JVM-tested against the demo backup (`ReadPagesTest`, `BudgetRulesTest`, `MonthPickingTest`). Swift tests:
`ReadPagesTests`; CI screenshots add `insights-patterns-*`, `insights-week-*`, `spending-page-*`, `budgets-*`, `activity-search-*`, `activity-filter-*`.
Not on iOS: splits (so no "Split a transaction" on a person page), an Overview week toggle. Nothing in batch J was compiled on the machine that wrote it
(no Swift toolchain there; the Kotlin ran under a small kotlinc harness); CI is the first compile of the Swift.

Batch K (import from other apps, functional first): Settings > Import from another app, and Choose backup file on first run when the file is a
Money Manager `.mmbak` or a Cashew `.sql`, pick a file the same way (the system file importer, any type). `ImportReader.swift` copies the file to a temporary
place, opens the copy read-only without a key (`SQLiteDB(readOnlyPath:)`), runs the queries :importer lists (`MoneyManagerSql.queries`, `CashewSql.queries`: the
same SQL as Android's `data/mm` and `data/cashew`) into a `SqlResults`, and deletes the copy whatever happens. What kind of file it is comes from its contents
(`PickedFileRules`: .kassa goes on to the usual restore, anything else unknown is refused in plain words). All reading rules are Kotlin: `MoneyManagerReader`
and `CashewReader` implement the importer's source interfaces over those answers, `CashewInterview` is the question order (found, account kinds, loans,
other currencies), and `ImportSession` (:shared, `ImportBridge.kt`) holds the plan and gives Swift flat data (`ImportQuestionData`, `ImportReviewData`).
`ImportModel` (`AppModel+Import.swift`) runs it on the app queue; `ImportScreens.swift` lays out the questions, the plan with its per-account and per-month
balance checks, and the done screen; text is `import.*` in Localizable.strings and `Wording+Import.swift`. Saving is `LedgerStore.replaceForImport`: ONE
transaction that mirrors `ImportRepository.replaceWith` (`ImportMerge.merge` in :shared: people stay, everything that points at old accounts and entries
goes, settings stay, the arrangement is dropped), then `DueSettings.forgetAccountReferences` and a re-read, which re-plans the notifications. Over existing
data (Settings) it asks "Replace everything?" first. Failures are `Failures.importFile` and `Failures.importSave`. Swift tests: `ImportTests` builds tiny
synthetic Money Manager and Cashew SQLite files at run time (nothing from a person's data) and checks scan, questions, plan, save, balances against the
source, rollback through `afterInsertHook`, the temporary copy and the model's first-run and Settings flows; `InteropTests` writes the demo backup as iOS
would (see docs/BACKUP_FORMAT.md for what that does and does not prove). CI screenshots add `import-found-*`, `import-accounts-*`, `import-review-*`,
`import-done-*` and the file `demo-from-ios.kassa`. JVM: `SqlSourcesTest` (:importer) and `ImportBridgeTest` (:shared). Not on iOS: reading the phone's
number style for amounts in the import (the US style is installed), a share-sheet or "Open in" entry for `.mmbak` files. Nothing in batch K was compiled
or run on the machine that wrote it (no Swift toolchain, and Gradle could not download kotlinx-datetime or JUnit); CI is the first compile.
