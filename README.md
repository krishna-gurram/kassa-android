# Kassa

A personal finance app for Android that works entirely offline. Manual entry first, with a little help; every
number traces back to entries you made. Built to replace Money Manager.

## Principles

- **No network.** The manifest strips `INTERNET`, and CI fails the build if it ever reappears in the APK.
- **Encrypted at rest.** Room on SQLCipher. The passphrase is random and wrapped by a key in the Android Keystore.
- **No cloud backup.** `allowBackup=false` and data extraction rules exclude everything. Kassa's own encrypted export
  is the backup path.
- **Plain arithmetic.** Money is whole cents in a `Long`. No floating point anywhere money is stored or summed.

## Layout

| Module | What it holds | Depends on |
| --- | --- | --- |
| `domain` | Money model, card statement cycles, budget periods, safe-to-spend | Kotlin stdlib only |
| `importer` | Money Manager backup mapping and the reconcile check | `domain` |
| `app` | Room database, Compose UI, Android plumbing | both |

`domain` and `importer` are plain JVM modules, so their tests run in seconds without an emulator:

```
./gradlew :domain:test :importer:test
```

## Building

Every push to `main` runs the tests, builds a signed release APK, and attaches it to a GitHub release named
`v0.2.<commit count>`. Install it on the phone from the release page; later releases install over it and keep your data.

Signing uses four repository secrets: `KASSA_KEYSTORE_BASE64`, `KASSA_KEYSTORE_PASSWORD`, `KASSA_KEY_ALIAS`,
`KASSA_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key, which can't update an installed copy.
Keep an offline copy of the keystore: losing it means the next build can't install over the current one.

Local builds need JDK 17 and the Android SDK; `./gradlew :app:assembleDebug` works with Android Studio's SDK.

## Importing from Money Manager

The `.mmbak` backup is a SQLite file. `AndroidMoneyManagerSource` copies it to the app cache, reads it read-only and
deletes the copy. `MoneyManagerImporter` maps it and computes every account balance and monthly total two ways, from the
backup's own rows and from what will be saved, so the review screen can show they match before anything is written.

Real backups never go in this repo. Tests use a synthetic source shaped like the real schema.

## What's in it

- Today: spent this week against a usual week, month to date, card payments due, cash and bank balances, items pending review
- Entries: number pad, top categories that collapse once picked, all-categories grid with search, splits
- Cards: total card balance split into balance payable and outstanding, by due date; record a payment of either or any amount
- Insights: red flags against your usual months, caps, trend, weekday pattern, repeating charges, weekly recap
- Accounts: assets, liabilities and net worth; unlimited accounts and groups, each with its own page of entries and running
  balance; holdings at prices you enter; people as receivables and payables
- Assist: bank notification capture, recurring bills that ask first, investment transfer prompts
- Settings: categories with emoji, built-in icons or your own photo, budgets, month start day, app lock, encrypted backup

Fonts: Hanken Grotesk, SIL Open Font License, see `third_party/`.
