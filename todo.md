# Multi-Architecture Build — TODO

## Phase 1: Maven Profiles in pom.xml

- [ ] 1. Add `<javafx.platform>` and `<platform.suffix>` properties to each profile in `pom.xml`
- [ ] 2. Add shade plugin filter rules for `linux-x86_64` profile (exclude non-target natives)
- [ ] 3. Add shade plugin filter rules for `linux-aarch64` profile
- [ ] 4. Add shade plugin filter rules for `mac-aarch64` profile
- [ ] 5. Add shade plugin filter rules for `mac-x86_64` profile
- [ ] 6. Add shade plugin filter rules for `win-x86_64` profile
- [ ] 7. Add shade plugin filter rules for `win-aarch64` profile
- [ ] 8. Add `<shadedClassifierName>` to shade config for platform-specific jar naming
- [ ] 9. Verify `mvn package -Plinux-x86_64` produces a smaller jar with correct natives
- [ ] 10. Verify `mvn package` (no profile) still produces the 45MB fat jar

## Phase 2: GitHub Actions Workflow

- [ ] 11. Create `.github/workflows/build.yml` with matrix builds and release job

## Phase 3: Final Verification

- [ ] 12. Verify all platform profiles produce correctly-sized jars
