# MojoLauncher — Server Hosting Prototype

A plain-Java (non-Android) prototype that implements the server-hosting
backend logic from the MojoLauncher specification.  
Runs on any Linux/macOS machine with Java 8+ — including Replit.

---

## Quick start on Replit

```bash
cd server-prototype
chmod +x build.sh run.sh test.sh
./build.sh          # compile sources and tests
./run.sh            # launch the interactive CLI
```

---

## What is included

| Component | Location |
|-----------|----------|
| `ServerInstance` model + persistence | `src/com/mojolauncher/server/model/` |
| `ServerJarDownloader` (Paper & Vanilla) | `src/com/mojolauncher/server/downloader/` |
| `ServerProcessManager` (start/stop/console) | `src/com/mojolauncher/server/lifecycle/` |
| `BackupManager` (zip world, restore) | `src/com/mojolauncher/server/backup/` |
| `ConsoleUI` (terminal console) | `src/com/mojolauncher/server/ui/` |
| Interactive CLI entry point | `src/com/mojolauncher/server/Main.java` |
| Unit/integration tests | `test/com/mojolauncher/server/` |

---

## Interactive CLI commands

```
create   — Create a new server instance (downloads jar automatically)
list     — List all instances with status
start    — Start a server instance (non-blocking, logs stream in background)
stop     — Graceful stop: save-all → stop → wait 30 s → force kill
console  — Attach an interactive console to a running server
backup   — Zip world/ folder to backups/<timestamp>.zip
versions — List available Paper or Vanilla versions
delete   — Delete an instance and all its data
quit     — Stop all running servers and exit
```

---

## Running tests

```bash
./test.sh
```

Tests cover:
- Instance directory creation and layout
- Metadata save/load round-trip
- EULA acceptance enforcement
- Backup zip creation, restore, and listing

---

## Testing the full flow manually

1. Run `./run.sh`
2. Type `create` and follow the prompts:
   - Name: `test1`
   - Type: `paper`
   - Version: `1.21.4` (or `versions` first to see what's available)
   - Accept the EULA: `yes`
3. Type `start` — select `test1`
4. Watch logs scroll (server is starting)
5. Type `console` — attach and type `list` to see connected players
6. Type `stop` — a backup zip is created automatically before shutdown

---

## Architecture notes

### Instance directory layout

```
~/mojo-servers/<uuid>/
  server.jar          ← downloaded server jar
  eula.txt            ← eula=true (only after explicit user acceptance)
  server.properties   ← template with configured port
  world/              ← Minecraft world data
  world_nether/
  world_the_end/
  logs/               ← per-run timestamped log files
  plugins/            ← drop plugins here
  backups/            ← timestamped zip backups
  instance.properties ← MojoLauncher metadata
```

### ServerProcessManager lifecycle

```
start()
  └─ ProcessBuilder → java -Xms... -Xmx... -jar server.jar nogui
       ├─ stdout stream thread → ConsoleListener.onLine()
       │                       → log file
       └─ exit-wait thread   → onServerStopped() / onCrash()

stopGraceful()
  └─ stdin ← "save-all\n"
  └─ sleep 2 s
  └─ stdin ← "stop\n"
  └─ waitFor(30 s) → destroyForcibly() if timeout
```

### PaperMC API flow

```
GET https://api.papermc.io/v2/projects/paper/versions/{version}/builds
  → pick latest build number
GET .../builds/{build}/downloads/paper-{version}-{build}.jar
  → download + SHA-256 verify
```

---

## Android integration

The Android-side equivalents live inside the main app module:

| Prototype class | Android equivalent |
|---|---|
| `server-prototype/.../ServerInstance` | `app_pojavlauncher/.../instances/ServerInstance.java` |
| `server-prototype/.../ServerJarDownloader` | `app_pojavlauncher/.../downloader/ServerJarDownloader.java` |
| `server-prototype/.../ServerProcessManager` | `app_pojavlauncher/.../lifecycle/ServerProcessManager.java` |
| `ConsoleUI` (terminal) | `app_pojavlauncher/.../fragments/ServerConsoleFragment.java` |
| `Main.java` | `app_pojavlauncher/.../fragments/ServerListFragment.java` |

On Android, `ServerProcessManager.ConsoleListener` callbacks are dispatched on
the background thread — use a `Handler(Looper.getMainLooper())` (already done
in `ServerListFragment` and `ServerConsoleFragment`) to post UI updates.

---

## Replit-specific notes

- Building the Android APK requires the Android SDK/NDK.  
  Use the `Build APK` workflow (already configured) for that.
- The server-prototype runs on the Replit Linux environment with the bundled JDK.
- Minecraft servers use significant RAM. For quick testing, use `Xmx=512M`.
- The server jar is downloaded at runtime — never bundled (licensing compliance).
