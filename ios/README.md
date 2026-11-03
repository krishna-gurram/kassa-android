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

## CI

The `ios-app` job (macos-15) installs XcodeGen, runs `xcodegen generate` in `ios/`, then `xcodebuild test` on an iPhone 16
simulator. `ScreenshotTests` draws the Accounts and Activity screens (light and dark) to `ci-output/shots`, which a step uploads to
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
