# Kassa: notes for working on this repo

Offline personal finance app for Android. Kotlin, Jetpack Compose, Room on SQLCipher. Owner: Krishna.

## Rules that matter
- **No network, ever.** The manifest removes `INTERNET`; CI fails if it appears. Don't add libraries that need it.
- **Money is `Long` cents.** Never `Double` for stored or summed amounts. Shares are millionths (`microShares`).
- **No currency is written anywhere.** Each phone picks one currency (Settings > Currency, or on first run); amounts
  are minor units and are never converted. Format with `Money.format`, put numbers in fields with `Money.toInput`,
  and use `Money.symbol` for a bare symbol. Never type `$` in UI code.
- **Nothing posts by itself.** Alerts, recurring bills and investment transfers become pending items the user confirms.
- **User data never goes in the repo.** Tests use synthetic data shaped like the Money Manager schema.

## Layout
- `domain/`: plain Kotlin rules (money, card cycles, periods, splits, insights, alert parsing). Fast JVM tests.
- `importer/`: Money Manager `.mmbak` mapping and reconcile. JVM tests.
- `app/`: Room database (`data/db`), repositories, Compose UI (`ui/`).

## Database changes
Bump the version in `KassaDatabase` and add a `Migration` next to the others. Columns must match the entity exactly
(type, nullability, indexes named `index_<table>_<column>`). CI installs the last published release on an emulator
first and upgrades over it, so a bad migration fails the build instead of reaching the phone.

## Build and release
- `./gradlew :domain:test :importer:test` runs the logic tests.
- Every build on `main` runs tests, builds a release APK signed with the Kassa key (four repo secrets), checks the
  certificate and the no-network rule, installs it over the last release on an emulator, then publishes `v0.2.<commit count>`
  as a GitHub release. Only the newest commit on `main` is published.
- Pushes from the Claude GitHub App sometimes don't trigger a run; start one with a `workflow_dispatch`.
- Build logs aren't readable through the API from Claude sessions, so failures upload logs to a draft release
  `diag-<run>`, and emulator screenshots/logcat go to `ci-<run>`.

## Navigation
`ui/Nav.kt` holds the tabs and a back stack of `Page`s in `NavViewModel`, so folding the phone keeps your place.
Anything you browse or fill in at length is a page with a back button (entry, card, account, person, category, week).
Tabs: Overview, Activity, Insights, Accounts, Settings, with Add as a floating button above the bar (hidden while a page is open). Cards live in Accounts. Settings is its own tab. Quick actions stay bottom sheets (pay a card, holdings, add an account). On the cover screen a page replaces
the tabs; at 720dp+ it opens beside the list it came from, except Add/Edit, which always takes the full screen. Anything that removes or replaces data asks first
(`ConfirmDialog`).

## One page per thing
Every category opens `CategoryPage`, every entry opens the entry page (`TransactionRow` onClick), every card `CardPage`,
every day `DayPage`, every store `InsightStorePage`, and "this week"/"this month" opens `SpendingPage`. Category lists
everywhere use `CategoryLines` in `ui/common/Breakdown.kt`. Reuse these; don't build a second version. "This week" starts on
the phone's first day of the week (`Ledger.weekStart`).

## Wording
Cards follow Money Manager: **Balance payable** is the unpaid last statement, **Outstanding** is charges since the
statement date, **Total balance** is both. Accounts show Assets, Liabilities and Net worth; people are Receivable or
Payable.

## Style
Plain, specific UI copy in sentence case. Let figures speak: labelled numbers, not sentences explaining them. Color is only for categories and state; primary actions are ink on ground.
Every screen works on the Fold's cover screen and switches to two panes at 600dp+ (720dp for list-detail).
