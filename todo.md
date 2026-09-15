# Multi-Architecture Build — TODO

## Phase 1: Maven Profiles in pom.xml

- [x] 1. Add `<javafx.platform>` and `<platform.suffix>` properties to each profile in `pom.xml`
- [x] 2. Add shade plugin filter rules for `linux-x86_64` profile (exclude non-target natives)
- [x] 3. Add shade plugin filter rules for `linux-aarch64` profile
- [x] 4. Add shade plugin filter rules for `mac-aarch64` profile
- [x] 5. Add shade plugin filter rules for `mac-x86_64` profile
- [x] 6. Add shade plugin filter rules for `win-x86_64` profile
- [x] 7. Add `<shadedClassifierName>` to shade config for platform-specific jar naming
- [x] 8. Upgrade JavaFX to `21.0.12` (needed — 21.0.2 has no `linux-aarch64` classifiers on Maven Central)
- [x] 9. Fix JNA filter paths (jar dirs use hyphens: `linux-x86-64`, `win32-x86-64`)
- [x] 10. Drop `win-aarch64` profile (JavaFX has never published `win-aarch64` classifiers — unresolvable)
- [x] 11. Verify `mvn package -Plinux-x86_64 -Djavafx.platform=linux` produces a smaller jar with correct natives
- [x] 12. Verify `mvn package -Plinux-aarch64 -Djavafx.platform=linux-aarch64` produces a smaller jar with correct natives
- [x] 13. Verify `mvn package -Pmac-aarch64` and `-Pmac-x86_64` produce correct jars with Mac-only natives
- [x] 14. Verify `mvn package -Pwin-x86_64` produces a correct jar with Windows DLLs only
- [x] 15. Verify `mvn package` (no profile) still produces the ~45MB fat jar

## Phase 2: GitHub Actions Workflow

- [x] 16. Create `.github/workflows/build.yml` with matrix builds and release job
- [x] 17. Remove `win-aarch64` from CI matrix (no JavaFX win-aarch64 artifacts)

## Phase 3: Final Verification

- [x] 18. Verify all remaining platform profiles produce correctly-sized jars