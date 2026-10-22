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
`v0.1.<run number>`. Install it on the phone from the release page; later releases install over it and keep your data.

Signing uses four repository secrets: `KASSA_KEYSTORE_BASE64`, `KASSA_KEYSTORE_PASSWORD`, `KASSA_KEY_ALIAS`,
`KASSA_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key, which can't update an installed copy.
Keep an offline copy of the keystore: losing it means the next build can't install over the current one.

Local builds need JDK 17 and the Android SDK; `./gradlew :app:assembleDebug` works with Android Studio's SDK.

## Importing from Money Manager

The `.mmbak` backup is a SQLite file. `AndroidMoneyManagerSource` copies it to the app cache, reads it read-only and
deletes the copy. `MoneyManagerImporter` maps it and computes every account balance and monthly total two ways, from the
backup's own rows and from what will be saved, so the review screen can show they match before anything is written.

Real backups never go in this repo. Tests use a synthetic source shaped like the real schema.

## Roadmap

1. Data, encryption, import, Activity and Accounts (this release)
2. Today, Cards, add/edit flow, categories with icon picker, budgets, splits and loans, Fold layouts
3. Notification capture, recurring approvals, investment transfer prompt
4. Insights, weekly recap, app lock, encrypted export

Fonts: Hanken Grotesk, SIL Open Font License, see `third_party/`.
