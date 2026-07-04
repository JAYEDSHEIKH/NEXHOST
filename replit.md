# MojoLauncher

An Android Minecraft: Java Edition launcher (fork of PojavLauncher) with an added **server-hosting prototype** that runs on Linux/Replit.

## Project overview

- **App module**: `app_pojavlauncher/` — Android application (Java + C/C++ via NDK)
- **Server prototype**: `server-prototype/` — plain-Java server manager runnable on Linux/Replit
- **Forge installer**: `forge_installer/` — helper module for installing Forge
- **Build system**: Gradle 8.14.3, Android Gradle Plugin 8.11.1, NDK 29.0.14206865

## Running in Replit

### Server hosting prototype (runs directly in Replit)

```bash
cd server-prototype
./build.sh    # compile all sources and tests
./run.sh      # launch interactive CLI server manager
./test.sh     # run all integration tests
```

### Building the Android APK

Use the **Build APK** workflow (configured in Replit).  
The first run downloads Gradle (~150 MB) and the build tools — subsequent runs are faster.

```bash
bash build_apk.sh
```

The signed debug APK is output to:
`app_pojavlauncher/build/outputs/apk/full/debug/`

## Android SDK setup (already configured)

| Component | Version/Location |
|-----------|-----------------|
| Android SDK | `~/android-sdk` |
| Build Tools | 36.0.0 |
| Platform | android-36 |
| NDK | 29.0.14206865 |
| Debug keystore | `app_pojavlauncher/debug.keystore` |
| SDK pointer | `local.properties` |

## Server hosting feature (feat/server-hosting-mvp)

New files added for server hosting:

### server-prototype/ (plain Java, runs on Replit)
- `src/com/mojolauncher/server/model/ServerInstance.java` — instance model + persistence
- `src/com/mojolauncher/server/model/ServerType.java` — PAPER / VANILLA enum
- `src/com/mojolauncher/server/downloader/ServerJarDownloader.java` — Paper API + Mojang
- `src/com/mojolauncher/server/lifecycle/ServerProcessManager.java` — process lifecycle
- `src/com/mojolauncher/server/backup/BackupManager.java` — zip backup/restore
- `src/com/mojolauncher/server/ui/ConsoleUI.java` — terminal console
- `src/com/mojolauncher/server/Main.java` — interactive CLI entry point
- `test/` — integration tests (8 tests, all pass)

### Android module additions
- `instances/ServerInstance.java` — Android-compatible instance model
- `downloader/ServerJarDownloader.java` — jar downloader with progress callback
- `lifecycle/ServerProcessManager.java` — Android process manager
- `fragments/ServerListFragment.java` — server list UI fragment
- `fragments/ServerConsoleFragment.java` — real-time console fragment

## User preferences

- Keep Android and prototype code separate; prototype is plain Java (no Android deps)
- Server jars are always downloaded at runtime, never bundled (licensing compliance)
- EULA acceptance must be explicit — never auto-written without user consent
