# HealthLog

Android-app (Kotlin + Jetpack Compose) for daglig helselogg: data fra Health Connect og
Garmin, dagbok, trender, rekorder og en regelbasert coach.

## Bygg

GitHub Actions kjører enhetstestene og bygger en debug-APK ved hver push – last den ned
under **Actions → siste kjøring → Artifacts → HealthLog-debug-apk**.

Lokalt (Android Studio eller kommandolinje):

```
./gradlew testDebugUnitTest assembleDebug
```

`app/build.gradle.kts` ble rekonstruert fra bibliotekversjonene i en tidligere APK.
