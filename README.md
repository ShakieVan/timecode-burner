# Timecode Burner

Schlanke Android-App, die einen laufenden Timecode im Format **MM:SS:mmm**
(Minuten:Sekunden:Millisekunden) dauerhaft unten links in ein Video einbrennt.

- Video aus der Galerie wählen oder direkt in die App teilen
- Vorschau mit Schieberegler – zeigt genau, wie das Ergebnis aussieht
- Größe S / M / L, dunkle Box oder nur Text mit Kontur
- Export als H.264-MP4 nach `Filme/Timecode/`, Ton bleibt erhalten
- Hardwarebeschleunigt über [Media3 Transformer](https://developer.android.com/media/media3/transformer), kein FFmpeg, keine Berechtigungen, ca. 3 MB

Ab Android 10. HDR-Aufnahmen werden beim Export nach SDR umgerechnet.

## Installieren

Die fertige APK liegt unter [Releases](../../releases/latest). Herunterladen,
öffnen und die Installation aus unbekannten Quellen erlauben.

## Selbst bauen

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Braucht JDK 17+ und das Android SDK (Plattform 37). Jeder Push baut die APK
per GitHub Actions; ein Tag `v*` hängt sie automatisch an ein Release.

## Hinweis zur Signatur

`app/signing.keystore` ist bewusst öffentlich im Repo (Passwort `android`),
damit lokale und CI-Builds sich gegenseitig aktualisieren können. Für eine
Veröffentlichung im Play Store einen eigenen, geheimen Schlüssel verwenden.

## Aufbau

| Datei | Aufgabe |
|---|---|
| `TimecodeRenderer.kt` | Zeichnet das Label, alles proportional zur kürzeren Bildseite |
| `TimecodeOverlay.kt` | Media3-Overlay, rendert das Label pro Frame und verankert es unten links |
| `MainViewModel.kt` | Video laden, Vorschau, Export, Speichern in der Galerie |
| `MainActivity.kt` | Compose-Oberfläche |
