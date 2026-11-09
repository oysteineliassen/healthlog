# Egen signeringsnøkkel

Uten disse hemmelighetene signeres appen med debug-nøkkelen i repoet (`app/debug.keystore`).
Den er åpen for alle og kan ikke brukes i Google Play.

**NB:** Første APK med ny nøkkel kan ikke installeres oppå den gamle. Ta sikkerhetskopi i
appen (Innstillinger → Lag sikkerhetskopi), avinstaller, installer den nye og gjenopprett.

## 1. Lag nøkkelen (én gang, på egen PC)

```
keytool -genkeypair -v -keystore healthlog-release.jks -alias healthlog \
  -keyalg RSA -keysize 4096 -validity 10000
```

Ta vare på filen og passordet et trygt sted. Mister du dem, kan du aldri oppdatere appen igjen.

## 2. Legg inn i GitHub

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Navn | Verdi |
|---|---|
| `KEYSTORE_BASE64` | innholdet av `base64 -w0 healthlog-release.jks` |
| `KEYSTORE_PASSWORD` | passordet til nøkkelfilen |
| `KEY_ALIAS` | `healthlog` |
| `KEY_PASSWORD` | passordet til nøkkelen (ofte det samme) |

Neste bygg bruker da nøkkelen automatisk.
