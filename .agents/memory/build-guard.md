---
name: Build guard for asset downloads
description: Gradle AfterEvaluate block for remote asset downloads is gated by a project property.
---

`app_pojavlauncher/build.gradle` — the `afterEvaluate` block that registers `AssetTaskRegistrar` (JRE downloads, Cacio jars, forge installer copy) is now wrapped:

```groovy
def shouldDownloadAssets = project.hasProperty('downloadAssets') && project.property('downloadAssets') == 'true'
if (!shouldDownloadAssets) { /* skip */ } else { /* register tasks */ }
```

**Why:** Without the guard, Gradle configuration-time network downloads fail in network-restricted or non-Android environments (Replit) making even `./gradlew tasks` fail.

**How to apply:**
- Local / Replit: `./gradlew :app_pojavlauncher:assembleDebug` (no flag — skips downloads)
- CI full build: `./gradlew :app_pojavlauncher:assembleDebug -PdownloadAssets=true`
