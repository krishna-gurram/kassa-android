# Kassa for iOS

SwiftUI app over the shared Kotlin code (`:domain` and `:importer`, exported as the static framework `KassaShared` by
`:shared`). The Xcode project is generated from `project.yml` with XcodeGen and is not checked in.

First milestone: a screen that formats a sample figure through the shared rules, and tests that the framework loads.

Stage B (first real screens, read-only): the app opens the encrypted store, builds `LedgerView` (in `:shared`, from the exported
`BackupData`) on one background queue, and lays out its flat figures. Tabs: Overview, Activity, Insights, Accounts, Settings; Accounts
and Activity are real, the rest say "Coming soon". An empty database shows the first-run screen: "Choose backup file" (file picker for
the `.kassa` type declared in `project.yml`, a password prompt for protected files) or "Start fresh". The eye hides every amount.
Figures, colours (`Tone`) and money text come from the shared Kotlin rules, so they equal Android's; Swift only lays out and words
them (`Wording.swift`, `Localizable.strings`, `Dates.swift` for the phone's date patterns). Not yet on iOS: currency picker,
Overview, Insights, Settings, search and filters in Activity, opening a row, archived accounts, restoring over existing data.

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
