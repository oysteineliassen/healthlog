# Huawei-vekt (AH100) i HealthLog

Tre filer i `app/src/main/java/no/healthlog/app/scale/`. Legg dem inn med samme mappestruktur:

| Fil | Hva den gjør |
|---|---|
| `Ah100Protocol.kt` | Selve protokollen: rammer, XOR mot MAC, AES-CTR, avkoding av målingen. Ren Kotlin. |
| `Ah100ScaleClient.kt` | Bluetooth: søker etter vekta, kobler til, håndtrykk, leverer én måling som `StateFlow`. |
| `ScaleScreen.kt` | Compose-skjerm «Vei deg»: profil første gang, så Start → tråkk på vekta → Lagre. |

`app/src/test/.../Ah100ProtocolTest.kt` er enhetstester. De dekoder blant annet ekte rammer fra en vekt med samme maskinvare (126,6 kg / 33,8 % på displayet), og alle passerer.

Koden er skrevet fra bunnen av ut fra protokollbeskrivelsen. Ingen openScale-kode er kopiert inn, så den kan brukes i HealthLog Pro.

## 1. AndroidManifest.xml

```xml
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- Android 11 og eldre -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

## 2. Koble skjermen inn

Legg til en rute i navigasjonen (eller en knapp på dagsregistreringen):

```kotlin
composable("scale") {
    val lastWeight by viewModel.lastWeightKg.collectAsState(initial = null)
    ScaleScreen(
        lastWeightKg = lastWeight,
        onSave = { r -> viewModel.saveScaleReading(r.weightKg, r.fatPercent) },
        onClose = { navController.popBackStack() },
    )
}
```

## 3. Lagre i Room

`saveScaleReading` skriver vekta til dagens rad, slik den manuelle registreringen gjør:

```kotlin
fun saveScaleReading(weightKg: Float, fatPercent: Float?) = viewModelScope.launch {
    val today = LocalDate.now()
    val entry = dao.getEntry(today) ?: DailyEntry(date = today)
    dao.upsert(entry.copy(weight = weightKg, bodyFat = fatPercent))
}
```

Har ikke entiteten en kolonne for fettprosent ennå, må du legge til `bodyFat: Float? = null` og en Room-migrering:

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE daily_entry ADD COLUMN bodyFat REAL")
    }
}
```

Navnene (`dao`, `DailyEntry`, `weight`, `daily_entry`) må tilpasses det du faktisk har.

## Første gang du kobler til

- **Glem vekta i Android sine Bluetooth-innstillinger** hvis den har vært paret med Huawei Health på denne telefonen. Den gamle paringen gir «Tilkoblingen ble avvist» (status 61). Du kan også avinstallere Huawei Health.
- Flyten er: trykk **Start**, tråkk på vekta så den våkner, og stå stille og **barbent** til tallet er klart. Uten hudkontakt får du vekt, men ikke fettprosent.
- Vekta bruker brukerplass 1. Første gang kan den be om «binding». Det skjer automatisk.

## Feilsøking

All trafikk logges i Logcat med taggen `Ah100Scale` (`←` er fra vekta, `→` er til vekta). Kommer det ingen måling, send meg loggen.
