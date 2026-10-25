#!/usr/bin/env bash
# Runs the instrumented tests (migrations, entries with splits, backup round trips) on the emulator.
# The debug build installs as app.kassa.money.debug, so it never touches the release app installed by smoke.sh.
set -u
mkdir -p ci-output
# Room writes the current schema while compiling; the migration tests read it from the test assets.
./gradlew --no-daemon :app:kspDebugKotlin > ci-output/device-tests-compile.log 2>&1 || { tail -60 ci-output/device-tests-compile.log; exit 1; }
cp app/schemas/app.kassa.money.data.db.KassaDatabase/*.json ci-output/ 2>/dev/null || true
./gradlew --no-daemon :app:connectedDebugAndroidTest > ci-output/device-tests.log 2>&1
STATUS=$?
# The readable summary of which test failed and why.
find app/build/outputs/androidTest-results -name "*.xml" -exec cp {} ci-output/ \; 2>/dev/null
if [ $STATUS -ne 0 ]; then
  grep -E "FAILED|Exception|expected" ci-output/device-tests.log | head -60
  exit $STATUS
fi
echo "Device tests passed."
