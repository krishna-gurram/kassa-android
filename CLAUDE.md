# Kassa: notes for working on this repo

Offline personal finance app for Android. Kotlin, Jetpack Compose, Room on SQLCipher. Owner: Krishna.

## Rules that matter
- **No network, ever.** The manifest removes `INTERNET`; CI fails if it appears. Don't add libraries that need it.
- **Money is `Long` cents.** Never `Double` for stored or summed amounts. Shares are millionths (`microShares`).
- **No currency is written anywhere.** Each phone picks one currency (Settings > Currency, or on first run); amounts
  are minor units and are never converted. Format with `Money.format`, put numbers in fields with `Money.toInput`,
  and use `Money.symbol` for a bare symbol. Never type `$` in UI code.
- **Nothing posts by itself.** Due bills and investment transfers wait on Overview until the user confirms them.
- **Nothing guesses an account.** A new entry, payment or card payment starts with no account unless the user set one
  (a card's own "pay from"). Lists may be ordered by use; nothing is preselected from a category or merchant.
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

## Layout
- `domain/`: plain Kotlin rules (money, card cycles, periods, splits, insights, input rules, account form, shares).
  Fast JVM tests.
- `importer/`: Money Manager `.mmbak` mapping and reconcile. Names and notes are cleaned and capped on the way in. JVM tests.
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
  after the smoke test. The debug build is `dev.krishnagurram.kassa.debug`, so it installs beside the real app.
- Every build on `main` runs tests, builds a release APK signed with the Kassa key (four repo secrets), checks the
  certificate and the no-network rule, installs it over the last release on an emulator, then publishes `v0.2.<commit count>`
  as a GitHub release. Only the newest commit on `main` is published.
- Pushes from the Claude GitHub App sometimes don't trigger a run; start one with a `workflow_dispatch`.
- Build logs aren't readable through the API from Claude sessions, so failures upload logs to a draft release
  `diag-<run>`, and emulator screenshots/logcat go to `ci-<run>`.

## Navigation
`ui/Nav.kt` holds the tabs and a back stack of `Page`s in `NavViewModel`, so folding the phone keeps your place.
Anything you browse or fill in at length is a page with a back button (entry, card, account, person, category, week).
Tabs: Overview, Activity, Insights, Accounts, Settings, with Add as a floating button above the bar. Over pages Add is hidden, except on card, account and category pages, where it starts the entry with that account or category picked (`AppController.presetFor`); the rail's Add does the same for the page beside the list. Cards live in Accounts. Settings is its own tab. Quick actions stay bottom sheets (pay a card, holdings, add an account). On the cover screen a page replaces
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

## Wording
Cards follow Money Manager: **Balance payable** is the unpaid last statement, **Outstanding** is charges since the
statement date, **Total balance** is both. A statement covers everything up to the day before its statement date; the
statement date itself starts the next cycle. Card figures are never clamped: paying past zero shows a green credit. Accounts show Assets, Liabilities and Net worth; people are Receivable or
Payable.

## Entries
The entry page edits only expenses, income and transfers. Buys and sales open the trade sheet (their shares and
price live in `trades`; trades from before database v7 have none and only their date can change), and payments to or
from a person open the payment sheet. `AppController.openEntry` routes each kind; never send another kind to the entry page.
Nothing guesses an account: a new entry starts with none and you pick it. Splits live on the entry page (Split row):
several people, who paid, equal or exact shares; a person's "Split a transaction" opens that same page.

## Style
Plain, specific UI copy in sentence case. Let figures speak: labelled numbers, not sentences explaining them. Color is only for categories and state; primary actions are ink on ground.
Every screen works on the Fold's cover screen and switches to two panes at 600dp+ (720dp for list-detail).
