# Kassa for iOS

SwiftUI app over the shared Kotlin code (`:domain` and `:importer`, exported as the static framework `KassaShared` by
`:shared`). The Xcode project is generated from `project.yml` with XcodeGen and is not checked in.

First milestone: a screen that formats a sample figure through the shared rules, and tests that the framework loads.

## CI

The `ios-app` job (macos-14) installs XcodeGen, runs `xcodegen generate` in `ios/`, then `xcodebuild test` on an iPhone 15
simulator. A pre-build script phase in the Kassa target runs `./gradlew :shared:link<Debug|Release>Framework<Target>`
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
