# Kassa: notes for working on this repo

Offline personal finance app for Android. Kotlin, Jetpack Compose, Room on SQLCipher. Owner: Krishna.

## Rules that matter
- **No network, ever.** The manifest removes `INTERNET`; CI fails if it appears. Don't add libraries that need it.
- **Money is `Long` cents.** Never `Double` for stored or summed amounts. Shares are millionths (`microShares`).
- **Nothing posts by itself.** Alerts, recurring bills and investment transfers become pending items the user confirms.
- **User data never goes in the repo.** Tests use synthetic data shaped like the Money Manager schema.

## Layout
- `domain/`: plain Kotlin rules (money, card cycles, periods, splits, insights, alert parsing). Fast JVM tests.
- `importer/`: Money Manager `.mmbak` mapping and reconcile. JVM tests.
- `app/`: Room database (`data/db`), repositories, Compose UI (`ui/`), notification listener (`assist/`).

## Database changes
Bump the version in `KassaDatabase` and add a `Migration` next to the others. Columns must match the entity exactly
(type, nullability, indexes named `index_<table>_<column>`). CI installs the last published release on an emulator
first and upgrades over it, so a bad migration fails the build instead of reaching the phone.

## Build and release
- `./gradlew :domain:test :importer:test` runs the logic tests.
- Every build on `main` runs tests, builds a release APK signed with the Kassa key (four repo secrets), checks the
  certificate and the no-network rule, launches it on an emulator, then publishes `v0.1.<run>` as a GitHub release.
- Pushes from the Claude GitHub App sometimes don't trigger a run; start one with a `workflow_dispatch`.
- Build logs aren't readable through the API from Claude sessions, so failures upload logs to a draft release
  `diag-<run>`, and emulator screenshots/logcat go to `ci-<run>`.

## Style
Plain, specific UI copy in sentence case. Color is only for categories and state; primary actions are ink on ground.
Every screen works on the Fold's cover screen and switches to two panes at 600dp+ (720dp for list-detail).
