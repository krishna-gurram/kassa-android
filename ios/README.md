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
people; loans, investments and fixed deposits are Android-only for now); every tab has a floating Add button that opens the entry page
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
share or bar. Patterns tiles, the week view and budgets are not on iOS yet.

Stage F (card, account and person pages): a tap on an Accounts row opens its page in place of the list, with a back button (`PageScreens.swift`, state in
`AppModel+Pages.swift`). A card with statement days shows Balance payable (with its due line), Total balance and Outstanding, a statement navigator and Pay card;
a plain account shows its balance, the month's In and Out, its entries and Close or Reopen (zero balance only, after a question); investments show only
balance and entries (a loan has its own page, stage G1). Pay card (`PaymentSheets.swift`) offers Balance payable, Total balance or another amount, picks the pay-from account (only the card's own
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
