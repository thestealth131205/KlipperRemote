# CLAUDE.md — KlipperRemote

## Wichtige Regeln

- **Niemals push-and-deploy ausführen ohne vorher explizit zu fragen.** Immer erst bestätigen lassen, bevor `push-and-deploy.sh` oder ein `gh release` ausgeführt wird.
- **Niemals `./gradlew` ausführen ohne explizite Anweisung** — Builds sind langsam und werden über CI/CD verwaltet.

## Projekt

Android-App zur Fernsteuerung von Klipper/Moonraker-3D-Druckern.

- **Package:** `com.klipperremote.app`
- **Min SDK:** 26, **Target/Compile SDK:** 36, **JVM:** 17
- **Tech:** Kotlin, Jetpack Compose, MVVM
- **CI:** `.github/workflows/build.yml` baut signierte Release-APK und veröffentlicht unter GitHub Releases

## Deployment

```bash
./push-and-deploy.sh   # git push + GitHub Release erstellen
```

Nur auf explizite Anweisung des Users ausführen.
