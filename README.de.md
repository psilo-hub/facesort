# Face Sort

![GitHub commit activity](https://img.shields.io/github/commit-activity/t/psilo-hub/facesort)
![GitHub last commit](https://img.shields.io/github/last-commit/psilo-hub/facesort)
![GitHub Downloads](https://img.shields.io/github/downloads/psilo-hub/facesort/total)
![GitHub Release Date](https://img.shields.io/github/release-date/psilo-hub/facesort)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Platform: Windows · Linux · macOS](https://img.shields.io/badge/Platform-Windows%20%E2%80%A2%20Linux%20%E2%80%A2%20macOS-informational.svg)]()
[![Download](https://img.shields.io/badge/Download-latest%20release-blue.svg)](https://github.com/psilo-hub/facesort/releases)

> [English](README.md) · Deutsch · [Français](README.fr.md) · [Español](README.es.md) · [Русский](README.ru.md) · [中文](README.zh.md)

Eine plattformübergreifende **Desktop-App**, die deine Fotosammlung nach Personen
sortiert. Sie erkennt Gesichter in deinen Fotos, gruppiert ähnliche automatisch
und lässt dich jede Person mit einem Namen versehen – danach kannst du deine
Bibliothek durchsehen, zusammenführen und aufräumen. Alles läuft lokal auf deinem
Rechner: **deine Fotos verlassen niemals deinen Computer.**

## Funktionen

- **Automatische Gesichtserkennung** beim Import – jedes erkannte Gesicht wird
  als kleines Vorschaubild gespeichert und kann benannt werden.
- **Automatische Gruppierung unbenannter Gesichter** – ähnliche Gesichter werden
  zu Clustern zusammengefasst, sodass du alle Fotos derselben Person in einem
  Durchgang benennen kannst.
- **Drei einfache Wege, Gesichter zu benennen:**
  - **Gesicht benennen** – arbeite Gruppen von unbenannten Gesichtern ab,
    die größten zuerst.
  - **Zufälliges Gesicht benennen** – durchstöbere zufällige unbenannte Gesichter
    und benenne die ausgewählten.
  - **Gesichter einem Namen hinzufügen** – wähle eine Person und benenne die
    unbenannten Gesichter, die ihr am ähnlichsten sind.
- **Duplikaterkennung** – vergleicht deine benannten Personen und erlaubt dir,
  Namen zusammenzuführen, die sich als dieselbe Person herausstellen.
- **Durchsuchen & prüfen** – sieh jede Person auf einen Blick, bohre dich zu den
  Fotos vor, öffne die Originale in deinem Viewer und entferne Benennungen.
- **Intelligenter Import** – durchsucht Unterordner rekursiv und überspringt
  bereits importierte Fotos (anhand des Inhalts, nicht des Dateinamens).
  Ein erneuter Import desselben Ordners ist daher wirkungslos, und dasselbe Foto
  wird nie doppelt gespeichert.
- **Pfadfilter** – beschränke das Benennen auf einen bestimmten Ordner oder
  Dateinamen.
- **Umbenennen & Benennung entfernen** – behebe einen Tippfehler überall auf
  einmal oder entferne ein Gesicht von einem Namen.
- **Fotos einer Person exportieren** – wähle unter *„Gesichter einem Namen
  hinzufügen"* eine Person aus und kopiere jedes Foto, das sie enthält, in einen
  Ordner deiner Wahl (Fotos, deren Originaldatei fehlt, werden stattdessen als
  Vorschaubilder exportiert).
- **Modelldownload beim ersten Start** – die eingebauten Gesichtserkennungsmodelle
  werden einmalig heruntergeladen (mit Fortschrittsfenster) und lokal gecacht.
- **Automatische Update-Prüfung** – informiert dich, wenn eine neue Version
  verfügbar ist, und zeigt die Versionshinweise.
- **Feedback-Tab** – sende Fehlerberichte und Feature-Wünsche direkt aus der App.
- **Danach komplett offline** – Erkennung und Vergleich laufen vollständig auf
  deiner CPU.

## Voraussetzungen

- **Java 17 oder neuer** (ein JDK, nicht nur ein JRE)
- Ein **64-Bit**-Desktop-Betriebssystem – Windows, Linux (x86_64 / aarch64)
  oder macOS (Intel / Apple Silicon)
- Für große Fotosammlungen werden einige GB freier Arbeitsspeicher empfohlen
- **FFmpeg** — in der App gebündelt (über ffmpeg4j); nichts muss installiert werden

## Download

Lade den neuesten Build von der
[**Releases**-Seite](https://github.com/psilo-hub/facesort/releases) – wähle das
Jar für deine Plattform (`facesort-<platform>.jar`). Fertig gebaute Jars für alle
Plattformen sind an jedem Release angehängt. Keine Kompilierung oder Einrichtung
nötig.

### Erster Start

1. Stelle sicher, dass **Java 17+** installiert ist (`java -version`).
2. Führe das Jar aus: `java -jar facesort-<platform>.jar` (oder Doppelklick).
3. Beim allerersten Start lädt Face Sort seine Gesichtserkennungsmodelle herunter
   und zeigt ein Fortschrittsfenster. Dafür ist einmalig eine Internetverbindung
   nötig.

Danach funktioniert alles offline.

## So benutzt du die App

Das Hauptfenster besteht aus mehreren Tabs. Arbeite sie grob in dieser
Reihenfolge ab:

1. **Bilder importieren** – drücke auf *Durchsuchen…*, um einen Ordner mit Fotos
   auszuwählen (JPG, JPEG, PNG, BMP, GIF, WebP; Unterordner werden rekursiv
   durchsucht), und drücke dann auf **Import**. Jedes erkannte Gesicht wird
   zusammen mit seinem Embedding und einem Vorschaubild gespeichert. Du kannst
   einen Import jederzeit stoppen; bereits importierte Dateien bleiben erhalten.
   Ein späterer erneuter Import desselben Ordners protokolliert nur neue Dateien.

2. **Gesicht benennen** – Die App gruppiert alle unbenannten Gesichter und zeigt
   den Vertreter der größten Gruppe. Gib einen Namen ein (vorhandene Namen
   werden beim Tippen erkannt) und drücke auf **Benennen**; die übrigen ähnlichen
   Gesichter der Gruppe werden dann angeboten, sodass du sie im selben Durchgang
   benennen kannst. Wechsle mit *Nächste Gruppe* zur nächsten Gruppe.

3. **Zufälliges Gesicht benennen** – eine zufällige Stichprobe unbenannter
   Gesichter. Klicke Gesichter an, um sie auszuwählen, gib einen Namen ein und
   drücke **Ausgewählte benennen**. Nutze das Feld *Pfadpräfix*, um die Stichprobe
   auf einen bestimmten Ordner oder eine Datei zu beschränken.

4. **Gesichter einem Namen hinzufügen** – Wähle links eine Person; die App reiht
   jedes unbenannte Gesicht nach Ähnlichkeit zum durchschnittlichen Embedding der
   Person und zeigt die besten Kandidaten. Wähle mehrere aus und drücke
   **Ausgewählte benennen** – klicke Gesichter einzeln an oder halte die
   **Umschalttaste** gedrückt und klicke auf das erste und das letzte Gesicht, um
   den gesamten Bereich dazwischen auszuwählen. Aktiviere das Kontrollkästchen
   *„Gesichter ausschließen, die eher einem anderen Namen entsprechen"*, um nur
   Gesichter anzubieten, deren beste Übereinstimmung die ausgewählte Person ist,
   und nutze das Feld *Pfadpräfix* zum Filtern nach Ordnern. Du kannst hier auch
   jede Person **Umbenennen…** oder mit **Bilder exportieren…** alle Fotos der
   ausgewählten Person in einen Ordner deiner Wahl kopieren (fehlende Originale
   werden als Vorschaubilder exportiert).

5. **Duplikate entfernen** – drücke **Start**, um Namenpaare nach Ähnlichkeit zu
   vergleichen. Entscheide bei jedem Paar: *Das sind Duplikate* (wähle dann,
   welcher Name überlebt – alle Gesichter werden zusammengeführt), *Das sind keine
   Duplikate* (bleibt dauerhaft gespeichert) oder *Überspringen*.

6. **Anzeigen** – durchstöbere deine benannte Sammlung. Jede Person ist eine Karte
   mit einem Vertretergesicht und der Anzahl benannter Gesichter. Ein Suchfeld
   filtert die Karten beim Tippen nach Namen, sodass die Liste auch bei hunderten
   von Personen übersichtlich bleibt. Klicke eine Karte,
   um die Fotos mit dieser Person zu sehen; klicke ein Foto, um das Original zu
   öffnen, oder nutze das Kontextmenü, um es von der Person zu *entfernen*.

7. **Einstellungen** – passe Gesichtserkennung, Gruppierung, Import und
   Modellparameter an; Details stehen in den Tooltips der Bedienelemente. Änderungen
   werden mit **Speichern** übernommen oder mit **Auf Standard zurücksetzen**
   zurückgesetzt.

8. **Feedback** – sende einen Fehlerbericht oder Feature-Wunsch an die
   Entwickler.

In der gesamten App kannst du jedes Gesicht per Rechtsklick **Original öffnen**;
balgt auf ein Gesicht zeigt den Pfad seines Quellbildes.

### Empfohlener Arbeitsablauf

```
Ordner importieren  →  Gesicht benennen / Zufälliges Gesicht benennen / Gesichter einem Namen hinzufügen (Personen benennen)
                    →  Duplikate entfernen (doppelte Personennamen zusammenführen)
                    →  Anzeigen (prüfen, Originale öffnen, Fehler entfernen)
```

## Wo deine Daten gespeichert werden

Alles liegt in einem `config/`-Ordner neben der App (wird beim ersten Start
angelegt) – kopiere ihn, um deine Bibliothek zu sichern:

| Pfad | Zweck |
|------|-------|
| `config/facesort.db` | Deine Bibliothek: Fotos, Gesichter, Namen, Benennungen |
| `config/facesort-config.json` | Deine Einstellungen |
| `config/latest-release.json` | Cache der automatischen Update-Prüfung |
| FaceAI-Modellcache | Heruntergeladene Modelle – standardmäßig `~/.djl.ai/cache` (Linux/macOS) oder `%USERPROFILE%\.djl.ai\cache` (Windows); über die Einstellung *FaceAI-Cacheordner* änderbar |

Alle Einstellungen können im Tab **Einstellungen** geändert werden (jede
Bedienelement hat einen Erklärungstooltip); sie werden in
`config/facesort-config.json` gespeichert. Die **Sprache** der Benutzeroberfläche
lässt sich dort ebenfalls umschalten.

Neben den Erkennungs- und Clustering-Parametern sind auch die Import-Budgets
einstellbar: die JPEG-Qualität gespeicherter Vorschaubilder und
Gesichtsausschnitte, die maximale Anzahl Frames pro Video und die Größe des
Gesichtsausschnitts – so lässt sich der Import zwischen Qualität und
Geschwindigkeit abwägen.

## Lizenz

Veröffentlicht unter der [MIT License](LICENSE).