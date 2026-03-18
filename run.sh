#!/bin/bash
set -e
cd "$(dirname "$0")"

./gradlew :app:installDebug
adb shell am start -n com.andforce.andclaw/com.afwsamples.testdpc.policy.locktask.SetupKioskModeActivity
