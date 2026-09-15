# Multi-Architecture Build Plan

## 1. Problem Statement

`mvn package` produces a **45MB fat jar** containing native libraries for every supported
platform. The original application jar is only **132KB** — the remaining ~45MB is
platform-specific native code from transitive dependencies that has no business being
shipped to any single user.

**Goal:** Produce per-platform jars (smaller, only the natives the user needs) **and**
keep a universal fat jar for broad distribution. Automate both via GitHub Actions, with
per-platform jars attached to releases on every push.

---

## 2. Findings — What's Inside the Fat Jar

### 2.1 Native library sources (109 files total)

| Dependency | Artifact | Size | Contains natives for | Has platform classifiers? |
|---|---|---|---|---|
| sqlite-jdbc | `org.xerial:sqlite-jdbc:3.45.1.0` | **13 MB** | Linux (x86_64, aarch64, arm, armv6, armv7, ppc64, x86), Linux-Musl, Linux-Android, Mac (aarch64, x86_64), Windows (x86_64, aarch64, armv7, x86), FreeBSD | **No** — all platforms in one jar |
| DJL pytorch-jni | `ai.djl.pytorch:pytorch-jni:2.5.1-0.31.0` | **4.8 MB** | linux-x86_64 (cpu, cpu-precxx11, cu124, cu124-precxx11), linux-aarch64 (cpu-precxx11), osx-aarch64 (cpu), win-x86_64 (cpu, cu124) | **No** — all platforms in one jar |
| JNA | `net.java.dev.jna:jna:5.14.0` | **1.8 MB** | ~20 platform/arch combos (linux-*, darwin-*, win32-*, freebsd-*, openbsd-*, sunos-*, aix-*) | **No** — all platforms in one jar |
| JavaFX | `org.openjfx:javafx-*-21.0.2` | ~10 MB | Platform-specific via classifier jars (`linux-aarch64`, `linux-x86_64`, `win`, `mac`, `mac-aarch64`) | **Yes** — uses Maven classifiers |
| DJL pytorch-native-cpu | `ai.djl.pytorch:pytorch-native-cpu:2.5.1` | 4 KB | Pointer jar — auto-downloads correct platform binary at runtime to DJL cache | N/A (runtime download) |

### 2.2 JavaFX platform selection

The current `pom.xml` depends on the classifierless `javafx-controls`, `javafx-fxml`
artifacts. Maven's dependency resolution auto-selects a platform classifier based on the
build host (the current build picked `linux-aarch64` — the build machine's arch). The
`note_about_maven.txt` mentions `-Djavafx.platform=win` for building on a mobile device.

For per-platform builds, `<javafx.platform>` must be set explicitly per profile.

### 2.3 DJL PyTorch native auto-download

`pytorch-native-cpu:2.5.1` is a 4KB pointer jar. At runtime, DJL downloads the correct
platform-specific PyTorch native binary (~200MB) into `~/.djl.ai/`. This means the fat
jar does **not** contain the actual PyTorch native library — only the JNI bridge
(`pytorch-jni`). Users will still need internet access on first run regardless of jar
type.

### 2.4 Size breakdown (approximate per-platform)

| Platform | Estimated jar size | Notes |
|---|---|---|
| linux-x86_64 | ~15 MB | sqlite (~3MB) + JavaFX (~10MB) + JNA (~150KB) + DJL jni (~2MB) |
| linux-aarch64 | ~15 MB | Similar to x86_64 |
| mac-aarch64 | ~13 MB | sqlite (~1MB) + JavaFX (~10MB) + DJL jni (~0.5MB) |
| mac-x86_64 | ~14 MB | sqlite (~1MB) + JavaFX (~10MB) + no DJL jni in current deps |
| win-x86_64 | ~18 MB | sqlite (~3MB) + JavaFX (~10MB) + DJL jni (~2MB) + Windows runtime DLLs |
| win-aarch64 | ~15 MB | sqlite (~1MB) + JavaFX (~10MB) + no DJL jni in current deps |
| **fat (all)** | **45 MB** | All of the above combined |

---

## 3. Architecture Decision — Profiles vs. Multi-Module

### Option A: Maven profiles in a single pom.xml (Recommended)

**How it works:** Define one Maven profile per platform. Each profile configures the
shade plugin to exclude native libraries for all other platforms, and sets
`<javafx.platform>` so Maven pulls the correct JavaFX classifier.

**Pros:**
- Minimal changes to existing project structure
- `mvn package` continues to produce the fat jar (default, no profile)
- `mvn package -Plinux-x86_64` produces the platform-specific jar
- Easy to maintain — all config in one file

**Cons:**
- Profiles in a single pom can get verbose
- No cross-compilation — you must build on the target platform (or a compatible one)

### Option B: Multi-module with aggregator pom

**How it works:** Create a parent `pom.xml` at the root and move the current
`pom.xml` into a module like `fat-jar/`. Create additional modules like
`linux-x86_64/`, `mac-aarch64/`, etc., each with its own `pom.xml` inheriting from
the parent. The aggregator root pom builds all modules.

**Pros:**
- Strict separation of concerns
- Each module can have completely different shade/assembly config
- Easier to add platform-specific post-processing scripts per module

**Cons:**
- Significant restructuring of the project
- Duplication of dependency declarations across module poms (or heavy parent pom management)
- More files to maintain
- The current `src/main/java` tree would need to live in a shared module or be referenced via `<sourceDirectory>` overrides

### Recommendation

**Option A (profiles)** is the right choice here. The platform jars are identical
except for which native libs are included — profiles are the exact tool for this.
Multi-module would only add complexity without meaningful benefit for this use case.

---

## 4. Implementation Plan

### Phase 1: Maven profiles in pom.xml

Add six platform profiles plus keep the default (fat) build. Each profile needs two
things: a `<javafx.platform>` property and shade plugin filter configuration.

#### 4.1 Profile structure

```xml
<profiles>
    <!-- Default: fat jar (no profile activation) -->
    <!-- Existing shade config stays as-is, produces 45MB jar -->

    <profile>
        <id>linux-x86_64</id>
        <properties>
            <javafx.platform>linux</javafx.platform>
            <platform.suffix>linux-x86_64</platform.suffix>
        </properties>
        <!-- shade filters + assembly config -->
    </profile>

    <profile>
        <id>linux-aarch64</id>
        <properties>
            <javafx.platform>linux</javafx.platform>
            <platform.suffix>linux-aarch64</platform.suffix>
        </properties>
    </profile>

    <profile>
        <id>mac-aarch64</id>
        <properties>
            <javafx.platform>mac</javafx.platform>
            <platform.suffix>mac-aarch64</platform.suffix>
        </properties>
    </profile>

    <profile>
        <id>mac-x86_64</id>
        <properties>
            <javafx.platform>mac</javafx.platform>
            <platform.suffix>mac-x86_64</platform.suffix>
        </properties>
    </profile>

    <profile>
        <id>win-x86_64</id>
        <properties>
            <javafx.platform>win</javafx.platform>
            <platform.suffix>win-x86_64</platform.suffix>
        </properties>
    </profile>

    <profile>
        <id>win-aarch64</id>
        <properties>
            <javafx.platform>win</javafx.platform>
            <platform.suffix>win-aarch64</platform.suffix>
        </properties>
    </profile>
</profiles>
```

#### 4.2 Shade plugin filter rules per profile

The shade plugin's `<filters>` section uses exclusion patterns. Each profile needs to
**keep only** native libs for its target platform and **exclude** everything else.

**Pattern matrix** — which paths to exclude per profile:

##### sqlite-jdbc natives (`org/sqlite/native/`)

| Profile | Exclude these paths |
|---|---|
| `linux-x86_64` | `org/sqlite/native/Mac/**`, `org/sqlite/native/Windows/**`, `org/sqlite/native/FreeBSD/**`, `org/sqlite/native/Linux-Android/**`, `org/sqlite/native/Linux-Musl/**`, `org/sqlite/native/Linux/aarch64/**`, `org/sqlite/native/Linux/arm/**`, `org/sqlite/native/Linux/armv6/**`, `org/sqlite/native/Linux/armv7/**`, `org/sqlite/native/Linux/ppc64/**`, `org/sqlite/native/Linux/x86/**` |
| `linux-aarch64` | Same as above but exclude `org/sqlite/native/Linux/x86_64/**` and keep `aarch64` |
| `mac-aarch64` | `org/sqlite/native/Linux/**`, `org/sqlite/native/Linux-*/**`, `org/sqlite/native/Windows/**`, `org/sqlite/native/FreeBSD/**`, `org/sqlite/native/Mac/x86_64/**` |
| `mac-x86_64` | Same as mac-aarch64 but keep `Mac/x86_64` instead of `Mac/aarch64` |
| `win-x86_64` | `org/sqlite/native/Linux/**`, `org/sqlite/native/Linux-*/**`, `org/sqlite/native/Mac/**`, `org/sqlite/native/FreeBSD/**`, `org/sqlite/native/Windows/aarch64/**`, `org/sqlite/native/Windows/armv7/**`, `org/sqlite/native/Windows/x86/**` |
| `win-aarch64` | Same as win-x86_64 but keep `Windows/aarch64` instead of `Windows/x86_64` |

##### JNA natives (`com/sun/jna/<platform>/`)

| Profile | Exclude these paths |
|---|---|
| `linux-x86_64` | `com/sun/jna/darwin-*/**`, `com/sun/jna/win32-*/**`, `com/sun/jna/freebsd-*/**`, `com/sun/jna/openbsd-*/**`, `com/sun/jna/sunos-*/**`, `com/sun/jna/aix-*/**`, `com/sun/jna/linux-aarch64/**`, `com/sun/jna/linux-arm/**`, `com/sun/jna/linux-armel/**`, `com/sun/jna/linux-loongarch64/**`, `com/sun/jna/linux-mips64el/**`, `com/sun/jna/linux-ppc/**`, `com/sun/jna/linux-ppc64le/**`, `com/sun/jna/linux-riscv64/**`, `com/sun/jna/linux-s390x/**`, `com/sun/jna/linux-x86/**` |
| `linux-aarch64` | Same pattern, but keep `linux-aarch64` and exclude `linux-x86-64` |
| `mac-aarch64` | `com/sun/jna/linux-*/**`, `com/sun/jna/win32-*/**`, `com/sun/jna/freebsd-*/**`, `com/sun/jna/openbsd-*/**`, `com/sun/jna/sunos-*/**`, `com/sun/jna/aix-*/**`, `com/sun/jna/darwin-x86-64/**` |
| `mac-x86_64` | Same as mac-aarch64 but keep `darwin-x86-64` instead of `darwin-aarch64` |
| `win-x86_64` | `com/sun/jna/linux-*/**`, `com/sun/jna/darwin-*/**`, `com/sun/jna/freebsd-*/**`, `com/sun/jna/openbsd-*/**`, `com/sun/jna/sunos-*/**`, `com/sun/jna/aix-*/**`, `com/sun/jna/win32-aarch64/**`, `com/sun/jna/win32-x86/**` |
| `win-aarch64` | Same as win-x86_64 but keep `win32-aarch64` instead of `win32-x86-64` |

##### DJL pytorch-jni natives (`jnilib/`)

| Profile | Exclude these paths |
|---|---|
| `linux-x86_64` | `jnilib/linux-aarch64/**`, `jnilib/osx-*/**`, `jnilib/win-*/**` |
| `linux-aarch64` | `jnilib/linux-x86_64/**`, `jnilib/osx-*/**`, `jnilib/win-*/**` |
| `mac-aarch64` | `jnilib/linux-*/**`, `jnilib/win-*/**` |
| `mac-x86_64` | Exclude all `jnilib/**` (no mac-x86_64 JNI lib in current deps) |
| `win-x86_64` | `jnilib/linux-*/**`, `jnilib/osx-*/**`, `jnilib/win-x86_64/cu124/**` (keep cpu only) |
| `win-aarch64` | Exclude all `jnilib/**` (no win-aarch64 JNI lib in current deps) |

##### Top-level Windows DLLs (JavaFX + Visual C++ runtime)

These are at the jar root: `decora_sse.dll`, `glass.dll`, `javafx_font.dll`,
`javafx_iio.dll`, `prism_*.dll`, `msvcp140*.dll`, `vcruntime140*.dll`,
`ucrtbase.dll`, `api-ms-win-*.dll`.

| Profile | Action |
|---|---|
| `linux-*`, `mac-*` | Exclude `*.dll` from the shade filter |
| `win-*` | Keep `*.dll` |

#### 4.3 Assembly plugin configuration

Add `maven-assembly-plugin` to each profile to produce a named artifact:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-assembly-plugin</artifactId>
    <version>3.7.1</version>
    <executions>
        <execution>
            <id>platform-jar</id>
            <phase>package</phase>
            <goals><goal>single</goal></goals>
            <configuration>
                <descriptors>
                    <descriptor>src/assembly/platform-jar.xml</descriptor>
                </descriptors>
                <finalName>facesort-${project.version}-${platform.suffix}</finalName>
                <appendAssemblyId>false</appendAssemblyId>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### 4.4 Assembly descriptor

Create `src/assembly/platform-jar.xml`:

```xml
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
                              http://maven.apache.org/xsd/assembly-2.2.0.xsd">
    <id>platform</id>
    <formats>
        <format>jar</format>
    </formats>
    <includeBaseDirectory>false</includeBaseDirectory>
    <fileSets>
        <fileSet>
            <directory>${project.build.directory}</directory>
            <outputDirectory>/</outputDirectory>
            <includes>
                <include>${project.build.finalName}.jar</include>
            </includes>
            <filtered>false</filtered>
        </fileSet>
    </fileSets>
</assembly>
```

Note: The assembly plugin with `<format>jar</format>` wraps the shaded jar. A simpler
alternative is to skip the assembly plugin entirely and just rename the shaded output
via the shade plugin's `<shadedArtifactAttached>` + `<shadedClassifierName>` options:

```xml
<configuration>
    <shadedArtifactAttached>true</shadedArtifactAttached>
    <shadedClassifierName>${platform.suffix}</shadedClassifierName>
    <!-- ... existing shade config ... -->
</configuration>
```

This produces `facesort-1.0-SNAPSHOT-${platform.suffix}.jar` directly. **This is the
recommended approach** — it avoids an extra plugin and an extra build step.

---

### Phase 2: GitHub Actions workflow

#### 2.1 Workflow file: `.github/workflows/build.yml`

```yaml
name: Build and Release

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  build-fat:
    name: Build fat jar
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - run: mvn -B package -DskipTests
      - uses: actions/upload-artifact@v4
        with:
          name: facesort-fat
          path: target/facesort-*-SNAPSHOT.jar
          if-no-files-found: error

  build-platform:
    name: Build ${{ matrix.platform }}
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        include:
          - platform: linux-x86_64
            os: ubuntu-latest
            javafx-platform: linux
          - platform: linux-aarch64
            os: ubuntu-24.04-arm
            javafx-platform: linux
          - platform: mac-aarch64
            os: macos-latest
            javafx-platform: mac
          - platform: mac-x86_64
            os: macos-13
            javafx-platform: mac
          - platform: win-x86_64
            os: windows-latest
            javafx-platform: win
          - platform: win-aarch64
            os: windows-11-arm
            javafx-platform: win
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build platform jar
        run: mvn -B package -P${{ matrix.platform }} -DskipTests
        env:
          JAVAFX_PLATFORM: ${{ matrix.javafx-platform }}
      - uses: actions/upload-artifact@v4
        with:
          name: facesort-${{ matrix.platform }}
          path: target/facesort-*-SNAPSHOT-*.jar
          if-no-files-found: error

  release:
    name: Create Release
    needs: [ build-fat, build-platform ]
    if: github.event_name == 'push' && startsWith(github.ref, 'refs/heads/')
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: artifacts/
          merge-multiple: true
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: build-${{ github.run_number }}
          name: Build ${{ github.run_number }}
          files: artifacts/*
          generate_release_notes: true
```

#### 2.2 Key CI decisions

- **Native libs require the target platform.** The shade filter approach works
  cross-platform (you can build a linux-x86_64 jar on Ubuntu even if the shade config
  excludes Mac DLLs), but JavaFX and DJL classifiers are resolved by Maven at build
  time based on `<javafx.platform>` and the OS. Building on the native runner is the
  safest approach.
- **`ubuntu-24.04-arm`** is GitHub's ARM64 Linux runner (currently in preview — check
  availability). If unavailable, linux-aarch64 can be built on `ubuntu-latest` with
  cross-compilation or skipped until the runner is GA.
- **`macos-13`** is the last Intel (x86_64) macOS runner. `macos-latest` is ARM64.
- **`windows-11-arm`** is GitHub's ARM64 Windows runner (check availability). If
  unavailable, win-aarch64 can be skipped.
- **Release trigger:** Every push to main/master creates a release with all built
  artifacts. Use `softprops/action-gh-release` which handles release creation and file
  upload.
- **`fail-fast: false`** ensures one platform failure doesn't cancel other platform
  builds.

---

### Phase 3: File changes summary

| File | Action | Description |
|---|---|---|
| `pom.xml` | **Modify** | Add 6 platform profiles with shade filter config and `<javafx.platform>` property |
| `src/assembly/platform-jar.xml` | **Create** | Assembly descriptor (if using assembly approach) |
| `.github/workflows/build.yml` | **Create** | CI workflow with matrix builds and release job |

No changes to Java source code. No changes to the existing shade plugin config for the
default (fat) build.

---

## 5. Implementation sequence

1. **Create the assembly descriptor** at `src/assembly/platform-jar.xml`
   (or skip if using shade's `<shadedClassifierName>`)

2. **Add profiles to `pom.xml`** — one at a time, verifying each:
   - Add `<properties>` block with `<javafx.platform>` and `<platform.suffix>`
   - Add shade plugin `<filter>` config inside the profile to exclude non-target natives
   - Add `<shadedClassifierName>${platform.suffix}</shadedClassifierName>` to shade config
   - Run `mvn package -Plinux-x86_64` and verify the jar contents with `jar tf`
   - Repeat for each platform

3. **Verify fat jar still works** — `mvn package` (no profile) should produce the
   same 45MB universal jar

4. **Create `.github/workflows/build.yml`** with the matrix build + release workflow

5. **Test the workflow** — push to a branch, verify all jobs pass, verify artifacts

---

## 6. Open questions / risks

| Issue | Mitigation |
|---|---|
| `ubuntu-24.04-arm` runner may not be available | Check GitHub docs; fall back to `ubuntu-latest` with QEMU or skip linux-aarch64 initially |
| `windows-11-arm` runner may not be available | Same — skip win-aarch64 initially if needed |
| DJL `pytorch-jni` has no `linux-x86_64` CPU-only variant — `cpu` and `cpu-precxx11` both ship | Keep both; `precxx11` is for older glibc. Filter only CUDA variants for CPU-only profiles |
| DJL `pytorch-jni` has no `osx-x86_64` native lib | The mac-x86_64 jar will work but PyTorch JNI will fail at runtime. Document this or find a DJL version that ships it |
| DJL `pytorch-jni` has no `win-aarch64` native lib | Same limitation. PyTorch on Windows ARM64 is not well supported upstream |
| `sqlite-jdbc` has no platform classifiers | Relies entirely on shade filters. Must carefully maintain exclusion lists |
| Shade filter patterns must not accidentally exclude `.class` files | Only exclude paths matching native lib directories — never use broad `**` patterns |
| `.gitignore` blocks `*.jar` | GitHub Actions uploads artifacts before they hit the workspace git context; no conflict. But local `target/` jars won't be git-tracked (already the case) |

---

## 7. Testing the plan

After implementation, verify each platform jar:

```bash
# Build a platform jar
mvn package -Plinux-x86_64 -DskipTests

# List native libs — should only contain linux-x86_64 natives
jar tf target/facesort-1.0-SNAPSHOT-linux-x86_64.jar | grep -E '\.(so|dll|dylib|jnilib)$'

# Verify no Mac/Windows/Linux-aarch64 libs leaked in
jar tf target/facesort-1.0-SNAPSHOT-linux-x86_64.jar | grep -E '(Mac/|Windows/|aarch64|\.dll$)'
# Should return nothing

# Compare sizes
ls -lh target/facesort-1.0-SNAPSHOT*.jar
# Platform jar should be ~15MB, fat jar ~45MB
```

Repeat for each platform profile, adjusting the grep patterns to expect only the
target platform's native paths.
