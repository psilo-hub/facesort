# Face Sort

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Platform: Windows · Linux · macOS](https://img.shields.io/badge/Platform-Windows%20%E2%80%A2%20Linux%20%E2%80%A2%20macOS-informational.svg)]()
[![Download](https://img.shields.io/badge/Download-latest%20release-blue.svg)](https://github.com/psilo-hub/facesort/releases)

A cross-platform **desktop app** that sorts your photo collection by people. It
detects faces in your photos, groups similar ones automatically, and lets you tag
each person with a name — then review, merge and clean up your library. Everything
runs locally on your own computer: **your photos never leave your machine.**

## Features

- **Automatic face detection** when you import photos — every detected face is
  kept as a small thumbnail, ready to tag.
- **Automatic grouping of unnamed faces** — similar faces are clustered together,
  so you can name all photos of the same person in one go.
- **Three easy ways to tag faces:**
  - **Put a name to a face** — work through groups of unnamed faces, largest first.
  - **Tag random face** — browse random unnamed faces and tag the ones you select.
  - **Face Name** — pick a person and tag the unnamed faces most similar to them.
- **Duplicate detection** — compares your named people and lets you merge names
  that turn out to be the same person.
- **Browse & review** — see every person at a glance, drill into the photos that
  contain them, open the originals in your system viewer, and untag faces.
- **Smart import** — scans subfolders recursively and skips photos it has already
  imported (by content, not by file name), so re-importing a folder is a no-op and
  the same photo is never stored twice.
- **Path filter** — restrict tagging to a specific folder or file name.
- **Rename & untag** — fix a typo everywhere at once, or remove a face from a name.
- **First-run model download** — the built-in face-recognition models are
  downloaded once (with a progress window) and cached locally.
- **Automatic update check** — lets you know when a new release is available.
- **Feedback tab** — send bug reports and feature requests straight from the app.
- **Fully offline afterwards** — all detection and matching runs locally on your CPU.

## Requirements

- **Java 17 or newer** (a JDK, not just a JRE)
- A **64-bit** desktop OS — Windows, Linux (x86_64 / aarch64) or macOS
  (Intel / Apple Silicon)
- A few GB of free RAM is recommended for large photo collections

## Download

Grab the latest build from the
[**Releases** page](https://github.com/psilo-hub/facesort/releases) — pick the jar
for your platform (`facesort-<platform>.jar`). Pre-built jars for all platforms
are attached to every release. No compilation or setup required.

### First start

1. Make sure **Java 17+** is installed (`java -version`).
2. Run the jar: `java -jar facesort-<platform>.jar` (or double-click it).
3. On the very first start, Face Sort downloads its face-recognition models and
   shows a progress window. This needs an internet connection once.

After that, everything works offline.

## How to use

The main window is a set of tabs. Work through them roughly in this order:

1. **Import images** — press *Browse…* to pick a folder of photos (JPG, JPEG, PNG, BMP,
   GIF, WebP; subfolders are scanned recursively), then press **Import**. Every
   detected face is stored together with its embedding and a thumbnail. You can
   stop an import at any time; already-imported files are kept. Re-importing
   the same folder later only records new files.

2. **Put a name to a face** — The app clusters all unnamed faces
   and shows the largest cluster's representative. Type a name (existing names
   are detected as you type) and press **Tag**; the remaining similar faces from
   the cluster are then offered so you can tag them in the same pass. Move to
   the next cluster with *Next cluster*.

3. **Tag random face** — a random sample of unnamed faces. Click faces to select
   them, type a name and press **Tag selected**. Use the *path prefix* field to
   restrict the sample to a particular folder or file.

4. **Face Name** — *put a face to a name.* Select a person on the left; the app
   ranks every unnamed face by similarity to that person's average embedding and
   shows the best candidates. Select several and press **Tag selected**. Use the
   *"Exclude faces closer to another name"* checkbox to only offer faces whose
   best match is the selected person, and the *path prefix* field to filter by
   folder. You can also **Rename...** any person here.

5. **Deduplicate** — press **Start** to compare name pairs by similarity. For
   each pair decide: *These are dupes* (then choose which name survives — all
   faces are merged), *These are not dupes* (remembered permanently), or
   *Skip*.

6. **View** — browse your tagged collection. Each person is a card showing a
   representative face and the number of tagged faces. Click a card to see the
   images containing that person; click an image to open the original, or use
   the right-click menu to *Untag* it from that person.

7. **Settings** — tune face detection, clustering, import and model parameters;
   see each control's tooltip for details. Changes are applied via **Save** or
   reset with **Reset to defaults**.

8. **Feedback** — send a bug report or feature request to the developers.

Throughout the app you can right-click any face or thumbnail to **Open Original**
in your system's default image viewer, and hovering a face shows the path of its
source image.

### Suggested workflow

```
Import a folder  →  Put a name to a face / Tag random face / Face Name (tag people)
                 →  Deduplicate (merge duplicate person names)
                 →  View (review, open originals, untag mistakes)
```

## Where your data is stored

Everything lives in a `config/` folder next to the app (created on first run) —
copy it to back up your library:

| Path | Purpose |
|------|---------|
| `config/facesort.db` | Your library: photos, faces, names, tags |
| `config/facesort-config.json` | Your settings |
| `config/CHANGELOG.md` | Cache used by the automatic update check |
| FaceAI model cache | Downloaded models — default `~/.djl.ai/cache` (Linux/macOS) or `%USERPROFILE%\.djl.ai\cache` (Windows); configurable via the *FaceAI cache dir* setting |

All settings can be changed in the **Settings** tab (each control has an
explanation tooltip); they are stored in `config/facesort-config.json`.

## License

Distributed under the [MIT License](LICENSE).