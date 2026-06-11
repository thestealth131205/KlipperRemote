# KlipperRemote

Eine Android-App (Jetpack Compose) zur Fernsteuerung von 3D-Druckern über
[Moonraker](https://moonraker.readthedocs.io/) / Klipper – Temperaturen setzen,
Webcam-Stream ansehen und Druckstatus überwachen.

## Features

- Temperatur-Übersicht (Extruder, Heizbett, Kammer) mit Soll-Werten setzen
- Webcam-Stream (MJPEG)
- Druckerstatus-Polling über Moonraker
- Dunkles, modernes UI mit farbigen Akzenten

## Build

```bash
./gradlew assembleDebug      # Debug-APK
./gradlew assembleRelease    # Signierte Release-APK (siehe Signierung)
```

Min SDK 26, Target/Compile SDK 34, JVM 17.

## Signierung

Release-Builds werden signiert. Die Signaturdaten kommen entweder aus einer
lokalen `keystore.properties` oder aus Umgebungsvariablen (CI):

| Property (`keystore.properties`) | Env-Variable (CI)    |
|----------------------------------|----------------------|
| `storeFile`                      | `KEYSTORE_FILE`      |
| `storePassword`                  | `KEYSTORE_PASSWORD`  |
| `keyPassword`                    | `KEY_PASSWORD`       |
| `keyAlias`                       | `KEY_ALIAS`          |

Lokal: `keystore.properties.example` nach `keystore.properties` kopieren und
ausfüllen. Weder der Keystore noch die Passwörter werden eingecheckt
(`.gitignore`).

## CI / Release

`.github/workflows/build.yml` baut bei jedem Push auf `main` eine signierte
Release-APK und veröffentlicht sie unter **Releases**. Dafür müssen folgende
GitHub-Actions-Secrets gesetzt sein:

- `KEYSTORE_BASE64` – Base64-kodierter Keystore (`base64 -w0 release.jks`)
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

## Deploy

```bash
./push-and-deploy.sh ["Commit-Nachricht"]
```

Committet & pusht nach GitHub, verfolgt den Actions-Build und zeigt den
veröffentlichten Release-Link. Authentifizierung über die GitHub CLI
(`gh auth login`) – es werden keine Secrets im Skript gespeichert.

## Lizenz

MIT
