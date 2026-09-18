# Face Sort

A cross-platform **JavaFX desktop app** for sorting your photo collection by
people. It detects faces in your photos, groups them automatically, and lets
you tag each person's face with a name so you can review, merge and clean up
your library — completely offline.

![Face Sort](docs/screenshots/main.png)

---

## Features

- **Face detection** during import — bounding boxes, confidence scores and
  face crops are stored for every detected person.
- **Automatic clustering** of unnamed faces — similar faces are grouped into
  clusters (approximate DBSCAN over an HNSW index) so you can name an entire
  group of photos of the same person at once.
- **Three tagging workflows:**
  - **Name Face** — work through clusters of unnamed faces, largest first, and
    tag them with a name.
  - **Random Tag** — browse random unnamed faces and batch-tag the ones you
    select.
  - **Face Name** — pick a person, then batch-tag the unnamed faces that are
    most similar to that person.
- **Duplicate-name detection** — compares every pair of named people by their
  average face embedding, so you can merge duplicates or mark pairs as
  distinct.
- **Browse & review** — see a grid of every person with a representative face
  and face count, drill into the images containing them, open the originals in
  your system viewer, and untag faces.
- **Smart import** — recursive folder import with parallel workers; files are
  de-duplicated by content hash (SHA-256), so re-importing a folder is a no-op
  and the same photo at several locations is recorded instead of duplicated.
- **Path-prefix filter** — restrict tagging to a specific folder or file name.
- **Rename & untag** — fix typos across all of a person's faces, or remove a
  face from a name.
- **First-run model download** — FaceAI's detection/recognition models are
  downloaded once (with a progress window) and cached locally.
- **Automatic update check** — notifies you when a new release is available.
- **Feedback tab** — send bug reports and feature requests straight from the
  app.
- **Fully offline afterwards** — all detection, embedding and similarity
  computation runs locally on your CPU.

## Screenshots

| | |
|---|---|
| ![Import](docs/screenshots/import.png) _Import tab_ | ![Name Face](docs/screenshots/nameface.png) _Name Face tab_ |
| ![Random Tag](docs/screenshots/randomtag.png) _Random Tag tab_ | ![Face Name](docs/screenshots/facename.png) _Face Name tab_ |
| ![Deduplicate](docs/screenshots/dedupe.png) _Deduplicate tab_ | ![View](docs/screenshots/view.png) _View tab_ |
| ![Settings](docs/screenshots/settings.png) _Settings tab_ | ![Model download](docs/screenshots/download.png) _First-run model download_ |

## Requirements

- **Java 17** or newer (JDK, not just a JRE)
- **Maven 3.6+**
- A 64-bit desktop OS — Windows, Linux (x86_64 / aarch64) or macOS
  (Intel / Apple Silicon)

Memory usage depends on the size of your photo collection; a few GB of free RAM
is recommended for large imports.

## Getting started

Face Sort depends on two libraries that are **not** published to Maven Central —
[FaceAI](https://github.com/psilo-hub/FaceAI) (detection & recognition engine)
and [rawGitHubFetcher](https://github.com/psilo-hub/rawGitHubFetcher). You must
install them into your local Maven repository once before building:

```bash
git clone --depth 1 https://github.com/psilo-hub/FaceAI.git faceai-deps
mvn -B install -DskipTests -f faceai-deps/pom.xml

git clone --depth 1 https://github.com/psilo-hub/rawGitHubFetcher.git rawgithubfetcher-deps
mvn -B install -DskipTests -f rawgithubfetcher-deps/pom.xml
```

### Build

```bash
mvn package          # creates the fat jar (all platforms' libs)
```

The resulting all-in-one jar is `target/facesort-1.0-SNAPSHOT.jar`.

To build an optimized, single-platform jar:

```bash
# Linux x86_64
mvn package -Plinux-x86_64 "-Djavafx.platform=linux"

# Linux aarch64
mvn package -Plinux-aarch64 "-Djavafx.platform=linux-aarch64"

# macOS (Apple Silicon / Intel)
mvn package -Pmac-aarch64 "-Djavafx.platform=mac-aarch64"
mvn package -Pmac-x86_64 "-Djavafx.platform=mac-x86_64"

# Windows x86_64
mvn package -Pwin-x86_64 "-Djavafx.platform=win"
```

Each profile produces a smaller jar at
`target/facesort-1.0-SNAPSHOT-<platform>.jar`. The `-Djavafx.platform` flag is
what lets you cross-build for another OS — for example, you can build a Windows
jar from a non-Windows machine by passing `-Djavafx.platform=win`.

### Run

While developing (from the project directory):

```bash
mvn javafx:run
```

Or run a built jar:

```bash
java -jar target/facesort-1.0-SNAPSHOT.jar
```

On the very first start Face Sort downloads the FaceAI models (a window shows
the download progress). After that everything works offline.

> Pre-built jars are also attached to every release of this repository via
> GitHub Actions — check the **Releases** page for `facesort-<platform>.jar`
> downloads.

## How to use

The main window is a set of tabs. Work through them roughly in this order:

1. **Import** — press *Browse…* to pick a folder of photos (JPG, JPEG, PNG, BMP,
   GIF, WebP; subfolders are scanned recursively), then press **Import**. Every
   detected face is stored together with its embedding and a thumbnail. You can
   stop an import at any time; already-imported files are kept. Re-importing
   the same folder later only records new files.

2. **Name Face** — *put a name to a face.* The app clusters all unnamed faces
   and shows the largest cluster's representative. Type a name (existing names
   are detected as you type) and press **Tag**; the remaining similar faces from
   the cluster are then offered so you can tag them in the same pass. Move to
   the next cluster with *Next cluster*.

3. **Random Tag** — a random sample of unnamed faces. Click faces to select
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

7. **Settings** — tune face-detection, clustering (HNSW), import and
   FaceAI-model parameters; see each control's tooltip for details. Changes are
   applied via **Save** or reset with **Reset to defaults**.

8. **Feedback** — send a bug report or feature request to the developers.

Throughout the app you can right-click any face/thumbnail to **Open Original**
in your system's default image viewer, and hovering a face shows the path of
its source image.

### Suggested workflow

```
Import a folder  →  Name Face / Random Tag / Face Name (tag people)
                 →  Deduplicate (merge duplicate person names)
                 →  View (review, open originals, untag mistakes)
```

## Where data is stored

Everything lives in a `config/` directory next to the app (created on first
run):

| Path | Purpose |
|------|---------|
| `config/facesort.db` | SQLite database: images, faces, names, not-dupes, thumbnails |
| `config/facesort-config.json` | User settings (JSON), see below |
| `config/CHANGELOG.md` | Cache used by the automatic update check |
| FaceAI cache | Downloaded models — default `~/.djl.ai/cache` (Linux/macOS) or `%USERPROFILE%\.djl.ai\cache` (Windows); configurable via the *FaceAI cache dir* setting |

### Configuration file

Settings are persisted as JSON in `config/facesort-config.json`. When the file
is absent, defaults are used, so the app works out of the box. Key settings you
can edit in the UI (or by hand):

| Key | Default | Description |
|-----|---------|-------------|
| `minBoundingBoxSize` | `80` | Min side length (px) of a kept face box |
| `minConfidence` | `0.8` | Min detection confidence to keep a face |
| `maxFacesPerImage` | `10` | Max faces stored per image (most confident win) |
| `maxDetectionDimension` | `1600` | Images larger than this are scaled down before detection |
| `clusteringThreshold` | `0.5` | Min similarity for two unnamed faces to cluster |
| `hnswM` / `hnswEfConstruction` / `hnswEfSearch` / `knnK` | `16` / `200` / `100` / `20` | HNSW index parameters used for clustering |
| `faceaiCacheDir` | *(blank)* | Where FaceAI models are cached; blank = default location |
| `thumbnailSize` | `256` | Max dimension (px) of stored image thumbnails |
| `maxImportThreads` | `4` | Parallel workers used during import |
| `minNameSimilarity` | `0.75` | Min similarity for a face to be offered for an existing name |
| `faceNameMaxImages` | `30` | Max candidate faces shown in the "Face Name" tab |
| `updateCheckEnabled` | `true` | Check for new releases on startup |

## Project structure

```
src/main/java/free/svoss/facesort/
├── FaceSortApp.java        # JavaFX application entry point
├── Launcher.java           # Main class for the fat jar
├── config/                 # JSON config loading/saving + settings model
├── db/                     # SQLite database + DAO layer
├── model/                  # FaceRecord, NameRecord, ImageRecord, ...
├── service/                # Import, clustering, naming, dedup, view, feedback
├── ui/                     # The eight main tabs + components
├── update/                 # Background update checker
└── util/                   # Hashing, image, embedding helpers
src/test/java/              # JUnit 5 tests for services, DAOs and utils
```

## Building on GitHub Actions

The repository ships a CI workflow (`.github/workflows/build.yml`) that, on
every push to `main`/`master`:

1. Installs the FaceAI and rawGitHubFetcher dependencies,
2. builds the cross-platform fat jar and single-platform jars for
   Linux (x86_64 + aarch64), macOS (aarch64 + x86_64) and Windows,
3. uploads them as artifacts and creates a **GitHub Release** with the jars
   (tag `build-<run number>`).

## License

Distributed under the [MIT License](LICENSE).