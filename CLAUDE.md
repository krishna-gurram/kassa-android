# Kassa: notes for working on this repo

Offline personal finance app for Android. Kotlin, Jetpack Compose, Room on SQLCipher.

## Rules that matter
- **No network, ever.** The manifest removes `INTERNET`; CI fails if it appears. Don't add libraries that need it.
- **Money is `Long` cents.** Never `Double` for stored or summed amounts. Shares are millionths (`microShares`).
- **No currency is written anywhere.** Each phone shows one currency (Settings > Currency); it starts as the phone's region
  currency when Kassa lists it (`Currencies.forLocale`, saved at once so moving regions never changes entered amounts), and
  otherwise the first-run screen asks (Start fresh and Choose backup file open the list until one is picked). Amounts are
  minor units and are never converted. Format with `Money.format`, put numbers in fields with `Money.toInput`, and use
  `Money.symbol` for a bare symbol. Never type `$` in UI code.
- **Numbers follow the phone's language, not the currency** (`domain/NumberText`, `Money.locale`): grouping and decimal mark
  (1,234.50, 1.234,50, 1 234,50, lakh grouping 12,34,567.50 for India, Bangladesh and Nepal, which java.text can't do). The
  symbol is always in front. Typed amounts, rates and share counts accept either mark: `Money.parseCents`, `InputRules.parseRate`
  and `Shares.parse` read what the person typed or pasted (`NumberText.parts`), and fields rewrite it with the phone's mark.
  The number pad keeps a point in its own text and only shows the phone's mark. **Files from other apps use
  `Money.parsePlain`** (a point is always a decimal there). Only currencies with two minor-unit digits are listed: JPY, KRW,
  VND, KWD, BHD and OMR need whole-unit or three-digit rules through fields, limits, importers and loan rounding first, and IDR
  would hit the 99,999,999.99 limit at about 6,000 US dollars. Tests pin the style with `Money.withLocale`; the Gradle test
  tasks run as en-US.
- **Nothing posts by itself.** Bills, card payments and loan payments wait on the Notifications page
  (`ui/today/Notifications.kt`, linked from Overview only when something waits) from their due day, never before, and
  stay while overdue; so do investment transfers and suggested bills. They wait until the user acts or clears them.
  Clearing never adds or pays anything. Phone notifications only tell; they never pay or add.
- **Nothing guesses an account.** A new entry, payment or card payment starts with no account unless the user set one
  (a card's own "pay from"). Nothing is preselected from a category or merchant.
- **Lists of names are A to Z** (`Names.order`, the phone's language): categories, subcategories, accounts, people,
  and the pickers on Add. Entries stay by date; spending breakdowns stay largest first. The one exception: once the
  user arranges their accounts (Accounts > Order > Your order), their order wins on Accounts and for the Overview tiles.
- **Every input is bounded.** Limits live in `domain/InputRules` (names 40, categories 30, notes 120, amounts
  99,999,999.99). Forms filter as you type (`NameField`, `AmountField`, `DigitsField` in `ui/common/Fields.kt`) and
  show the problem under the field; repositories check the same rules again and throw `IllegalArgumentException`
  with a message, which `LedgerViewModel.change` shows. Any text that shows a name or note sets `maxLines` and
  `TextOverflow.Ellipsis`.
- **Hide balances: every amount goes through `Money.format`; never build amount text by hand.** `Money.toInput` is for
  fields (typed amounts are never masked), `Money.sample` only for examples that aren't anyone's money. While hidden,
  `Money.format` returns `Money.MASK` (no sign, no symbol): a Compose state in `Settings.hideAmounts` backs
  `Money.hideAmounts`, so every composable that formats money redraws at once. So don't split or measure formatted text,
  and give any color or sign that follows a figure (over budget, a loss, net worth below zero) through `figureColor`,
  `favourColor`, `balanceColor` or `cardTint`, which turn plain ink while hidden. The eye lives in `PageTop` and
  `TabTop` (`amounts = false` only where a page shows none), so a new screen gets it free. While hidden, wording must not give
  the size or state away either: no "Paid in full", "extra", "over budget", "N% ahead" or arrows and plus signs next to a masked
  figure, and no zero shown as blank where others show the mask.
- **User data never goes in the repo.** Tests use synthetic data shaped like the Money Manager schema.

## Built for public release
Kassa is headed to the Play Store, so code is written for strangers' phones, not one Fold:
- No personal assumptions: nothing tied to the owner's banks, cards, Money Manager setup or expense-splitting habits. Nothing is recognised by a brand or a name: where Kassa can't classify something, the user picks.
  Defaults must make sense for someone starting fresh.
- Locale-aware: dates, weekdays and numbers come from the phone's locale (never a hardcoded `Locale.US`);
  currencies cover what real users need. New UI copy should be easy to move into `strings.xml` later.
- Accessible: content descriptions on icon buttons and glyph buttons (a "‹", "⋯" or "⌫" text glyph gets
  `clearAndSetSemantics { contentDescription = ... }` or is an `IconButton` with an `Icon`), and **every tap target at least
  48dp** (`IconButton`, `heightIn(min = 48.dp)`, or a 48dp cell with the smaller visual drawn inside it, as in the calendar days,
  the bill day grid and the color swatches). Layouts survive 200% fonts: no fixed height on anything that holds text (use
  `heightIn(min = ...)` and let it wrap or ellipsize); a chart whose labels grow with the text uses `scaledDp`; big numerals and
  the pad keys use `scaledSp` (follows the font setting up to 130% because their box can't grow); fixed widths are `widthIn(min = ...)`.
  Overview may scroll at large fonts, which is fine; it still fits without scrolling at the default size.
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
- `domain/`: plain Kotlin rules (money, card cycles, periods, splits, insights, input rules, account form, shares,
  `AccountsOverview` for the Accounts tab's groups and totals).
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
  Every table is in it, including waiting investment transfers (optional in the file, so older backups read none). A new table
  or column that holds user data needs a `BackupContents` field (optional) and a line in the round-trip tests.
  An import over existing data (`ImportRepository.replaceWith`) replaces everything that points at the old accounts and entries
  (holdings, trades, transfers, loan terms and payments, bills, split shares) in the same transaction, and a restore or import
  calls `Settings.forgetAccountReferences` (Overview pins, account order, cleared notices) and `DueNotifier.forgetPosted`.
- `app/ui`: `MainActivity` (theme, lock) → `AppRoot` → `Shell` (tabs) with `Routes.kt` mapping tabs and pages to
  screens. `AppController` holds navigation and the actions screens call; `LedgerViewModel` exposes one `Ledger`
  (everything screens read) and runs changes. Phone preferences are read through `LocalSettings`.
- Big screens keep their state in a plain state holder next to them (`EntryState` for the entry page,
  `AccountForm` for the account forms) so the composables only lay things out.

## Database changes
Schema 9 added `accounts.maturityDay` and `closedDay` (nullable epoch days); older backups read them as null. Schema 10
added `loan_terms.countAllAsSpending` (NOT NULL DEFAULT 0, so the entity says `@ColumnInfo(defaultValue = "0")`) and
`principalCategoryId`, and `loan_payments.countAllAsSpending` (nullable) and `principalCategoryId` (see Loans); older
backups read the defaults.
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
  as a GitHub release. The app's `versionName` is `1.0.<build number>` and `versionCode` is `100 + build number` (the release tag
  stays `v0.2.<count>` until the workflow changes; the tag is not the version people see). Only the newest commit on `main` is published.
- Pushes from the Claude GitHub App sometimes don't trigger a run; start one with a `workflow_dispatch`.
- Build logs aren't readable through the API from Claude sessions, so failures upload logs to a draft release
  `diag-<run>`, and emulator screenshots/logcat go to `ci-<run>`.

## Opening, locking and recreation
- **The database can fail to open** (Keystore key lost after a reset or restore, "file is not a database", a failed upgrade).
  `KassaApplication.openDatabase()` returns `DatabaseOpen.Ready` or `Failed` and caches it; `MainActivity` opens it off the
  main thread and shows `ui/start/CantOpenScreen.kt` ("Kassa can't open its data": Try again, Restore from a backup, Start over;
  the last two ask first and erase the unreadable database and its key, then the first-run screen follows). `DatabaseKey.get`
  never makes a new key over an existing database (`KeyPolicy.decide`, JVM tests); it throws `KeyUnreadable` instead.
- **App lock** state survives recreation (saved instance state), so folding, rotating or a theme change never re-prompts; a
  cold start locks, and so does coming back after more than a minute (`hiddenAt` is saved too, for a killed process).
- **FLAG_SECURE** is set on the window while App lock or Hide balances is on (reactive, all Android versions), so recents
  thumbnails and screenshots never show balances. Screenshots are blocked while it is set.
- **Typed input survives recreation:** `EntryState` has a saver (`EntryState.saver`), the first-run account form and the pay-card
  and loan-payment sheets use `rememberSaveable` (`AccountFormSaver`, `LoanPaymentFormSaver`), and which sheet is open lives in
  `NavViewModel` (`AppController` only forwards to it). Other sheets reopen empty after a fold. New forms should do the same.
- **Crash file** (`last-crash.txt`, attached to feedback if the user ticks it) holds exception class names and stack frames only,
  never message text (`CrashReport.describe`).

## Starting screen
`Theme.Kassa.Starting` (parent `Theme.SplashScreen` from `androidx.core:core-splashscreen`, Apache 2.0, covered by the "Android
Jetpack libraries" notice) shows the icon on a disc over the ground color, light and dark (`drawable/ic_splash.xml`,
`splash_disc`), and `MainActivity` calls `installSplashScreen()` before `super.onCreate` to switch to `Theme.Kassa`.

## Navigation
`ui/Nav.kt` holds the tabs and a back stack of `Page`s in `NavViewModel`, so folding the phone keeps your place.
Anything you browse or fill in at length is a page with a back button (entry, card, account, person, category, week).
Tabs: Overview, Activity, Insights, Accounts, Settings, with Add as a floating button above the bar. Overview, top to bottom: Notifications (when anything waits), Spending/Income with three top-category tiles, Upcoming (three rows; five unfolded), and up to eight pinned account tiles, all the same height. It fits the screen without scrolling (Upcoming drops rows first) and scrolls only when even one row won't fit; unfolded with nothing beside it, the tiles sit in a second column (`ui/today/AccountTiles.kt`; Settings › Home screen also picks the tab Kassa opens on). Over pages Add is hidden, except on card, account and category pages, where it starts the entry with that account or category picked (`AppController.presetFor`); the rail's Add does the same for the page beside the list. Cards live in Accounts. Settings is its own tab. The eye (Hide balances) sits in the top bar of every tab and page that shows amounts. Quick actions stay bottom sheets (pay a card, holdings, add an account). On the cover screen a page replaces
the tabs; at 720dp+ it opens beside the list it came from, except Add/Edit, which always takes the full screen. Anything that removes or replaces data asks first
(`ConfirmDialog`).

## Accounts tab
Top: Assets, Liabilities, Net worth (orange with its minus below zero). Then a foldable card for each kind the user has,
from `AccountsOverview`: Cash and bank (checking, savings, cash, prepaid, fixed deposit), Investments, Other assets,
Credit cards, Loans, Other debts, then People and a Closed group (folded by default; fold state lives in `NavViewModel`).
600dp+: assets on the left, liabilities on the right; the cover screen is one column. Rows are name and main figure, with
one second line: card "Pay <balance payable>" and "Due <date>", loan "Pay <payment>" and "Due <date>" (only with terms),
fixed deposit "Matures <date>". Orange only for something payable and a payment due today or overdue. No bars,
percentages, trends or forecasts here. Debts show what is owed, with no minus.
Settings > Data > "Account to person" lists the user's own plain accounts (not cards, loans or investments, A to Z); the user picks
one and names the person, and it becomes a person with the same balance while the account is archived. Nothing is matched by name.
Fixed deposit is an asset with an optional maturity date. Close account (account and card pages) needs a zero balance
(holdings included) and asks first; Reopen undoes it. A closed account (`closedDay`) is out of Assets, Liabilities, Net
worth, every picker and the Overview tiles (`Account.isActive`); its entries stay everywhere. Nothing is deleted.

**Order and arrange accounts.** One quiet line under the Assets, Liabilities and Net worth strip reads "Order  A to Z" (or
Largest first, Due soonest, Your order) with a chevron, 48dp tall and tappable, and opens the "Order accounts" bottom sheet
(`ui/accounts/OrderSheet.kt`; "Overview tiles follow this order."). Radio rows: Name (A to Z), Balance (Largest first),
Due date (soonest first, the rest A to Z), and Your order, which has an Arrange pill (Edit once arranged). Picking Name,
Balance or Due sets that sort and clears any arrangement; picking Your order or tapping Arrange/Edit closes the sheet and
starts arrange mode. The footer has "Reset to A to Z" (applies at once, then a "Back to A to Z" message with Undo that
restores the previous sort and arrangement: `Messages.withAction`, provided as `LocalMessages`, shown by `MessageHost`)
and Close. There is no reverse-direction button; the sort says what it gives. "Collapse all" stays at the end of the line.
Arrange mode (`ui/accounts/ArrangeAccounts.kt`): the top becomes "Arrange" with one Done button, the strip and Order line
are hidden, and the hint reads "Drag the handles. Open a group to move its accounts. Saved as you go." Every group is one
row (chevron, name and count, drag handle); an open group lists its accounts as a name and a handle, with no balances.
Which groups are open is local (`rememberSaveable`). Every drop saves at once; Done only leaves the mode. Entering it from
a sort first keeps what is shown as Your order. Dragging is hand-written (`ui/common/Reorder.kt`: `ReorderState`,
`reorderItem`, `ReorderHandle`, auto-scroll at the screen edges; the index maths is the plain-Kotlin `ReorderMath` with JVM
tests, no library): groups drag only among their own column (assets or liabilities at 600dp+), accounts only inside their
group. Each handle is 48dp, reads "Reorder <name>" and offers TalkBack the actions Move up and Move down (missing at the
ends), which call the domain's one-step moves. `domain/AccountArrangement` holds the group order and each group's account
order (ids); a drag stores the whole new order through `Settings.setAccountArrangement`. Anything new (account or kind of
group) goes to the end, A to Z; closed or deleted ids are ignored. Settings stores it as one string (`accountArrangement`)
and sets the sort to Your order (stored as CUSTOM); picking any other sort clears it. Overview tiles follow it (group
order, then row order; People last). It rides in the `.kassa` backup beside the month start and currency
(`accountArrangement`, absent in older files). The old stored reverse flag is no longer read.

## One component per job
Every screen uses the same piece for the same job, so a change in one place looks the same everywhere. Before
writing a new control, find the existing one; if it doesn't fit, change it rather than adding a second version.
- Dates: `MonthCalendar` (the entry page's date panel) and `DateRow` (Today, Yesterday, Other day; opens the same
  calendar). Never Material's `DatePicker`. **Writing a date for people goes through `ui/common/Dates.kt`** (`shortDate`,
  `shortDateYear`, `weekdayDate`, `weekdayDateLong`, `monthYear`, `monthYearShort`, `monthShort`), which asks the phone for its
  own best pattern ("14 Nov" in the UK, India and the Gulf, "Nov 14" in the US). Never `DateTimeFormatter.ofPattern("MMM d")`,
  a hardcoded `Locale`, or a month name glued to a year; whole dates use `ofLocalizedDate`. No ordinals ("5th") or other
  English-only grammar in new copy.
- Amounts `AmountField`, names `NameField`, short numbers `DigitsField` (`ui/common/Fields.kt`); the entry and bill
  pages use `NumberPad`.
- Categories: one picker everywhere (`ui/common/CategoryPicker.kt`). `CategoryPickerPanel` (search, icon grid) and
  `SubcategoryPickerPanel` ("No subcategory", subcategories, "+ New") hold no state about what is being edited: the caller
  passes what is picked and gets the id back. Add entry and the bill page put them on their own state (`EntryState`
  `CategoryPanel`/`SubcategoryPanel`); a bottom sheet wraps its form in `CategoryPickerHost` and uses `CategoryChoiceRow`
  (a `CategoryFieldRow`: icon tile, small label, "Parent › Child", chevron, 56dp), which swaps the sheet body for the same
  panels with a back arrow and returns on a pick. Loan setup, Edit details and the payment sheet use it for interest,
  principal and escrow; a null choice shows its default's name ("Loan interest", "Escrow", "Same as interest (X)"). "+ New"
  creates through `LocalAddSubcategory` (AppRoot provides it). Never build a text row and a list for a category.
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
  per-loan override would need a `loan_terms` column. (Its category row is the shared picker, with "Escrow" as the default.) A loan's payment entries carry `LoanNotes`, which also keeps a steady
  escrow or flat-rate interest out of the suggested bills.
- **Loan page** (`AccountPage` for a loan with terms; `ui/loans/LoanPage.kt`): Outstanding balance (ink, no minus), a progress bar
  only when the original amount is known, then Payment (with its due date), Paid off, Interest (Profit) paid so far (the linked
  interest entries) and remaining, the Payment schedule (next 6 rows, "Show full schedule"), Terms with each dated change (tap to
  remove), and Record a payment, Change terms, Edit details, Correct balance. Figures come from `LoanSummary.of`, so they use the
  terms in effect on each date. Activity rows of a payment carry a small tag (principal, interest, escrow, charge) and open the
  payment sheet (`LoansState.tagFor`); never split such an entry (the person page's list leaves them out).
- **Count the whole payment as spending** (schema 10): a switch on the loan (setup form and Edit details, off by default; the same
  `SpendingSwitch` and `LoanCategoryFields` in both) that makes spending figures also count each payment's principal, under a
  **Principal category** (`loan_terms.principalCategoryId`; null means the same category as the interest, offered as "Same as
  interest (name)" at the top of the picker). Accounting never changes: the principal stays a TRANSFER to the loan, interest and
  escrow stay EXPENSEs, balances, net worth, card and loan figures and the loan engine read the real entries. The payment sheet
  has the switch as a one-time override (`loan_payments.countAllAsSpending`: null follows the loan, 1 on, 0 off; choosing what the
  loan already does clears it), a Principal category row while it counts (`loan_payments.principalCategoryId`, null means the
  loan's), and a "Counts as spending" line in its summary. **One rule, `domain/CountedSpending`:** `apply(entries, payments,
  terms, categories)` returns the entries as spending sees them, with each counted principal entry turned into an EXPENSE in
  its principal category (payment's own, else the loan's, else where that payment's interest went, else the interest category,
  else "Loan interest"; same id, account, date, amount and note). Only the entry a `loan_payments` row names as its principal,
  and only a TRANSFER, can change; income, balance corrections (`ADJUST_*`) and every other transfer never do. Nothing is
  rewritten, so it reaches past payments and turning it off restores every figure. Every figure of spending reads `Ledger.counted`
  (and `Ledger.counted(list)`, `LedgerViewModel.countedBetween`, which the month, week, day and category pages load) or applies
  the rule itself (Activity's totals and rows): Overview, Insights, budgets, spending lines, Activity. Cards, bills, repeats, due
  notices and balances keep reading `Ledger.recent`. A new spending figure reads the counted list and keeps its explicit
  `EXPENSE`/`INCOME` filter. Activity rows of a counted principal are expenses in the principal category, tagged "principal" with
  "counts as spending" under the category; on the loan's own page the transfer says "Counts as spending".
- **Change terms** (`Page.ChangeTerms`, `LoanChangeForm`): Rate, Payment, Interest type or Refinanced, from a date (future allowed,
  never before the loan's start). A rate change asks what the lender kept and previews the new payment or payoff from
  `LoanEngine.rateChange`. It saves one `loan_term_changes` row (`LoanRepository.addChange`); payments before it keep their split
  because the engine reads each date's terms. A change that gives a term always saves the payment that goes with it.
- **Edit details** (`LoanDetailsForm`, `LoanDetailsSheet`): name, due day (moves the next payment date), pay from, escrow, fee, early
  repayment charge, interest category, the spending switch and principal category, original amount and start. It reuses the
  setup form's pieces (`PayFromPicker`, `DueDayField`, `CostFields`, `LoanCategoryFields`, `ProgressFields`).
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
and sets ONE inexact alarm (request code 103, `setAndAllowWhileIdle`; Kassa has no `SCHEDULE_EXACT_ALARM` permission, so a notification or the daily reminder may arrive a few minutes late) for the earliest next one, or a daily look
at 00:05 so a card's new statement is noticed. `refresh` is called at boot, update, time and zone change, app start, Settings
changes, `LedgerViewModel.change` and restore. Posted keys live in the private `kassa_due` prefs. A tap opens the card page,
the loan's page with its payment sheet, or the bill (`MainActivity` `ACTION_OPEN_DUE` to `AppController.openDue`). Android's
permission is asked once, with an explanation, after the first card with a due day or loan with terms is saved
(`LedgerViewModel.askNotifications`); if refused, Settings > Notifications shows "Notifications are off for Kassa".
A due notification is `VISIBILITY_PRIVATE` with a public version that says only "Payment due" (no amount, no account or bill
name, no count), so nothing reaches a locked screen; its private text omits the amount while Hide balances is on.

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
