# Changelog

Alle nennenswerten Änderungen an Sony GPS Link. Der Abschnitt zur jeweiligen Version
wird beim Release automatisch als Release-Text auf GitHub verwendet und erscheint
so auch im Update-Dialog der App. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/).

Regeln für Einträge:
- Aus Nutzersicht schreiben: Was ändert sich beim Fotografieren, nicht welche Klasse.
- Die ersten Zeilen zählen am meisten — der Update-Dialog zeigt nur die ersten 1500 Zeichen.
- Kategorien: **Neu**, **Geändert**, **Behoben**, **Entfernt**. Leere Kategorien weglassen.
- Ein Bugfix nennt kurz das sichtbare Symptom, damit Betroffene sich wiedererkennen.

## [Unreleased]

### Neu
- **Sprachwechsel:** Die App gibt es jetzt auf Deutsch und Englisch. Unter
  Einstellungen → Sprache lässt sich die Sprache unabhängig vom System wählen;
  Benachrichtigungen, Kachel und Protokoll folgen mit. Standard bleibt die
  Systemsprache, ab Android 13 auch über die System-Einstellung „App-Sprachen“.

## [0.6.0] - 2026-09-15

### Neu
- **Diagnose exportieren** in den Einstellungen unter „Fehlersuche“: Gerätemodell,
  Android-Version, Berechtigungen, Einstellungen und das Protokoll der letzten
  Sitzungen als Textdatei teilen — gedacht für Fehlerberichte auf GitHub.
- Das Protokoll wird jetzt dauerhaft gespeichert (rund 1 MB Verlauf) und enthält auch
  Ereignisse, die bei geschlossener App passieren: Auto-Verbinden, Reconnects,
  Systemneustarts des Dienstes. „Protokoll löschen“ entfernt es wieder.

### Behoben
- **Akku-Modus bei ausgeschaltetem Display:** Die Kamera verlor regelmäßig die
  GPS-Position und bekam sie kurz darauf wieder. Ursache war der schlafende
  Prozessor, der den 5-Sekunden-Sendetakt anhielt. Die App hält jetzt während einer
  Sitzung die CPU wach (nicht das Display). Die Ersparnis des Akku-Modus bleibt,
  denn sie kommt vom pausierenden GPS-Chip.
- Im Akku-Modus mit 60 s Intervall stoppte das Senden schon bei einem leicht
  verspäteten Fix. Die Grenze liegt jetzt bei drei Intervallen, mindestens 60 s.

## [0.5.0] - 2026-09-15

### Neu
- Eigener Einstellungsbildschirm: Auto-Verbinden mit Berechtigungsführung, Akku
  sparen mit wählbarem Intervall (10/20/30/60 s), GPX-Aufzeichnung, Tracks teilen,
  Abkürzung zur Akku-Optimierung, Versionsinfo.

### Geändert
- Die Kamera erhält unabhängig vom Fix-Intervall alle 5 s ein Paket mit dem
  letzten Standort. Im Akku-Modus zeigte sie vorher zwischen zwei Fixes „kein GPS“.
- Der Hauptbildschirm zeigt die aktiven Modi nur noch als Einzeiler; die Schalter
  sind in die Einstellungen gewandert.

## [0.4.0] - 2026-09-15

### Neu
- **Akku sparen:** GPS seltener und mit ausgeglichener Priorität abfragen.
- **Schnelleinstellungs-Kachel:** Sitzung aus der Benachrichtigungsleiste starten
  und stoppen, ohne die App zu öffnen.

## [0.3.0] - 2026-09-15

### Neu
- **In-App-Update:** Die App prüft einmal täglich GitHub auf eine neue Version,
  lädt die APK herunter und öffnet den Installer.

## [0.2.0] - 2026-09-15

### Neu
- **Gespeicherte Kamera:** Nach dem ersten erfolgreichen Verbinden wird die Kamera
  gemerkt und kann ohne Suche wieder verbunden werden.
- **Auto-Verbinden:** Ein Hintergrund-Scan startet die Sitzung, sobald die
  gespeicherte Kamera in Reichweite ist.
- **GPX-Aufzeichnung:** Jede Position einer Sitzung wird in eine GPX-Datei
  geschrieben, um Fotos nachträglich zu geotaggen.

## [0.1.1] - 2026-04-17

### Geändert
- Releases werden über die Datei `release.version` geschnitten statt über
  gepushte Tags.

## [0.1.0] - 2026-04-17

### Neu
- Erste Version: BLE-Suche nach Sony-Kameras, GPS-Übertragung alle 5 s,
  APO-Keepalive, automatischer Reconnect, Foreground-Service.

[Unreleased]: https://github.com/Asdoos/SonyGpsApp/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/Asdoos/SonyGpsApp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/Asdoos/SonyGpsApp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/Asdoos/SonyGpsApp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/Asdoos/SonyGpsApp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Asdoos/SonyGpsApp/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/Asdoos/SonyGpsApp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Asdoos/SonyGpsApp/releases/tag/v0.1.0
