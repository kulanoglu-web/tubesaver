# ClearTube

Werbefreier YouTube-Downloader für Android (Kotlin + Jetpack Compose), ähnlich wie TubeMate.

## Funktionen

- **YouTube-Suche** direkt in der App (ohne API-Key), mit Nachladen weiterer Ergebnisse
- **Link einfügen** oder per „Teilen“ aus der YouTube-App an ClearTube senden
- **Qualitätsabfrage vor dem Download**: alle Videoauflösungen (MP4/WEBM) und Audioformate (M4A/Opus) mit Dateigröße
- **Speicherort-Abfrage vor dem Download** (Ordnerwahl über das Android-Speicherzugriffsframework, wird gemerkt, bleibt bei jedem Download änderbar)
- **Mehrere Quellen/Verbindungen**: Datei wird in 4-MB-Segmenten über 6 parallele Verbindungen geladen; bei Fehlern wird automatisch der nächste alternative Stream versucht
- Video und Audio werden ohne Neukodierung zu einer Datei zusammengefügt
- Downloads laufen als Vordergrunddienst mit Benachrichtigung, Abbrechen möglich
- Keine Werbung, keine Tracker

## Bauen

1. Ordner in **Android Studio** (Ladybug oder neuer) öffnen und Gradle-Sync abwarten.
2. Gerät oder Emulator (Android 8.0 / API 26 oder neuer) wählen und **Run** drücken.
3. APK: *Build → Build APK(s)*.

Die Datei `gradle-wrapper.jar` liegt nicht bei. Android Studio lädt Gradle 8.9 anhand der
`gradle-wrapper.properties` automatisch; für die Kommandozeile einmal `gradle wrapper` ausführen.

## Wichtige Hinweise

- YouTube ändert seine Schnittstellen häufig. Wenn Suche oder Downloads plötzlich nicht mehr gehen,
  in `app/build.gradle.kts` die Version von `NewPipeExtractor` auf den neuesten Release-Tag
  von https://github.com/TeamNewPipe/NewPipeExtractor/releases anheben.
- Die Version `v0.24.8` und die API-Namen habe ich aus dem Gedächtnis gesetzt und nicht kompiliert.
  Beim ersten Sync können kleine Anpassungen nötig sein.
- „Mehrere Quellen“ bedeutet hier: parallele Verbindungen zum YouTube-CDN plus automatischer Wechsel auf
  alternative Streams. YouTube liefert eine Datei normalerweise nur von einem Server-Host aus.
- Audio wird im Originalformat (M4A/Opus) gespeichert, nicht nach MP3 umgewandelt.
- Das Herunterladen verstößt gegen die YouTube-Nutzungsbedingungen und kann Urheberrechte berühren.
  Nutze die App nur für eigene Inhalte, Creative-Commons-Videos oder mit Erlaubnis der Rechteinhaber.
