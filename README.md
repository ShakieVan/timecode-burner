# Timecode Burner

Schlanke Android-App, die einen laufenden Timecode im Format **MM:SS:mmm**
(Minuten:Sekunden:Millisekunden) dauerhaft in Videos einbrennt.

- Ein oder mehrere Videos aus der Galerie wählen oder direkt in die App teilen
- **Stapelverarbeitung:** mehrere Videos nacheinander mit denselben Einstellungen
- Vorschau mit Schieberegler – zeigt genau, wie das Ergebnis aussieht
- Position in jeder Ecke (Standard: oben links), Größe S / M / L, dunkle Box oder Text mit Kontur
- Auflösung verkleinern: 2160p, 1440p, 1080p, 720p, 480p, 360p (nie vergrößert)
- Alle Einstellungen bleiben gespeichert
- Export als H.264-MP4 nach `DCIM/Timecode/` (interner Speicher), Ton bleibt erhalten
- Updates direkt in der App
- Hardwarebeschleunigt über [Media3 Transformer](https://developer.android.com/media/media3/transformer), kein FFmpeg, ca. 3 MB

Ab Android 10. HDR-Aufnahmen werden beim Export nach SDR umgerechnet.

## Installieren

Die fertige APK liegt unter [Releases](../../releases/latest). Herunterladen,
öffnen und die Installation aus unbekannten Quellen erlauben.

## Updates in der App

Ab Version 1.1 unter **Einstellungen > Updates** (Zahnrad oben rechts):

1. Installierte und verfügbare Version samt Versionshinweisen ansehen
2. **Update herunterladen**: Der Download läuft auch beim App-Wechsel weiter
3. Die App prüft SHA-256-Prüfsumme (`SHA256SUMS.txt` im Release) und Signatur
4. **Update installieren**: Beim ersten Mal führt **Installationsfreigabe erteilen**
   direkt zur passenden Android-Einstellung
5. Nach dem Update bietet die App an, die Freigabe wieder auszuschalten

Timecode sucht höchstens einmal am Tag automatisch. Ein GitHub-Konto oder
Token ist nicht nötig.

## Selbst bauen

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Braucht JDK 17+ und das Android SDK (Plattform 37). Jeder Push auf `main`
baut die APK per GitHub Actions und hängt sie an das Release zur aktuellen
`versionName` (in `app/build.gradle.kts`). Neue Version = `versionName` und
`versionCode` hochzählen; Versionshinweise kommen aus `releases/v<version>.md`.

## Hinweis zur Signatur

`app/signing.keystore` ist bewusst öffentlich im Repo (Passwort `android`),
damit lokale und CI-Builds sich gegenseitig aktualisieren können. Für eine
Veröffentlichung im Play Store einen eigenen, geheimen Schlüssel verwenden.

## Aufbau

| Datei | Aufgabe |
|---|---|
| `TimecodeRenderer.kt` | Zeichnet das Label, alles proportional zur kürzeren Bildseite |
| `TimecodeOverlay.kt` | Media3-Overlay, rendert das Label pro Frame und verankert es in der gewählten Ecke |
| `Settings.kt` | Auflösungsstufen, gespeicherte Einstellungen |
| `MainViewModel.kt` | Videos laden, Vorschau, Stapel-Export, Speichern in der Galerie |
| `MainActivity.kt` | Compose-Oberfläche |
| `UpdateManager.kt` | Update-Prüfung, Download, Prüfsumme/Signatur, Installation |
| `UpdateUi.kt` | Einstellungen > Updates, Hinweis-Banner |
