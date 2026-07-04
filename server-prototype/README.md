# MojoLauncher — Server Hosting Prototype

A plain-Java (non-Android) prototype that implements the server-hosting backend logic from the MojoLauncher specification.  
Runs on any Linux/macOS machine with Java 8+ — including Replit.

---

## Quick start on Replit

```bash
cd server-prototype
chmod +x build.sh run.sh test.sh
./build.sh          # compile sources and tests
./test.sh           # run all unit/integration tests
```

### Interactive CLI mode
```bash
./run.sh
```

### HTTP REST API mode (recommended for Android integration)
```bash
./run.sh --http              # starts on port 8080
./run.sh --http --port 9000  # custom port
```

---

## HTTP REST API

Base URL: `http://localhost:8080`

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/instances` | List all instances |
| `POST` | `/api/v1/instances` | Create instance + download jar (async, returns 202) |
| `GET` | `/api/v1/instances/{id}` | Get instance metadata and status |
| `POST` | `/api/v1/instances/{id}/start` | Start the server process |
| `POST` | `/api/v1/instances/{id}/stop` | Graceful stop (save-all → stop → force-kill) |
| `POST` | `/api/v1/instances/{id}/backup` | Create a world zip backup |
| `GET` | `/api/v1/instances/{id}/console` | Last N log lines (`?lines=100`) |
| `GET` | `/api/v1/instances/{id}/console/stream` | Server-Sent Events real-time log stream |
| `POST` | `/api/v1/instances/{id}/console/command` | Send a command to the server stdin |
| `DELETE` | `/api/v1/instances/{id}` | Stop + delete all instance files |

### Create request body

```json
{
  "name":       "my-server",
  "type":       "paper",
  "version":    "latest",
  "xms":        "512M",
  "xmx":        "1024M",
  "acceptEula": true
}
```

- `type`: `"paper"` (default) or `"vanilla"`  
- `version`: a specific version like `"1.21.4"` or `"latest"` (resolves automatically)  
- `acceptEula`: **must be `true`** — explicit EULA acceptance is required (never auto-accepted)

### curl examples

```bash
# List instances
curl http://localhost:8080/api/v1/instances

# Create a Paper 1.21.4 instance (downloads jar in background)
curl -X POST -H "Content-Type: application/json" \
  -d '{"name":"test1","type":"paper","version":"1.21.4","xms":"512M","xmx":"512M","acceptEula":true}' \
  http://localhost:8080/api/v1/instances

# Create a Paper instance using the latest version
curl -X POST -H "Content-Type: application/json" \
  -d '{"name":"latest-paper","type":"paper","version":"latest","acceptEula":true}' \
  http://localhost:8080/api/v1/instances

# Check status (watch "status" field: queued → downloading → ready)
curl http://localhost:8080/api/v1/instances/<id>

# Start the server (after status is "ready")
curl -X POST http://localhost:8080/api/v1/instances/<id>/start

# Poll last 50 log lines
curl "http://localhost:8080/api/v1/instances/<id>/console?lines=50"

# Stream logs in real-time (Server-Sent Events)
curl -N http://localhost:8080/api/v1/instances/<id>/console/stream

# Send a command to the server (e.g. list online players)
curl -X POST -H "Content-Type: application/json" \
  -d '{"command":"list"}' \
  http://localhost:8080/api/v1/instances/<id>/console/command

# Graceful stop
curl -X POST http://localhost:8080/api/v1/instances/<id>/stop

# Create a world backup
curl -X POST http://localhost:8080/api/v1/instances/<id>/backup

# Delete instance (must be stopped first)
curl -X DELETE http://localhost:8080/api/v1/instances/<id>
```

### Instance status values

| Status | Meaning |
|--------|---------|
| `queued` | Download queued, not started yet |
| `downloading: 42%` | Jar download in progress |
| `ready` | Jar downloaded and verified; server can be started |
| `error: <msg>` | Download or startup failure |
| `STOPPED` | Process not running |
| `STARTING` | Process started; waiting for "Done" line |
| `RUNNING` | Server fully started and accepting connections |
| `STOPPING` | Graceful stop in progress |
| `CRASHED` | Process exited unexpectedly |

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
- EULA acceptance enforcement (cannot write eula.txt without explicit accept)
- Backup zip creation, restore, and listing

---

## What is included

| Component | Location |
|-----------|----------|
| `ServerInstance` model + persistence | `src/com/mojolauncher/server/model/` |
| `ServerJarDownloader` (Paper & Vanilla, SHA-256 verify) | `src/com/mojolauncher/server/downloader/` |
| `ServerProcessManager` (start/stop/console/crash detection) | `src/com/mojolauncher/server/lifecycle/` |
| `BackupManager` (zip world, restore) | `src/com/mojolauncher/server/backup/` |
| `ServerApi` (embedded HTTP REST + SSE) | `src/com/mojolauncher/server/api/` |
| `ConsoleUI` (terminal console for CLI mode) | `src/com/mojolauncher/server/ui/` |
| Interactive CLI entry point | `src/com/mojolauncher/server/Main.java` |
| Unit/integration tests | `test/com/mojolauncher/server/` |

---

## Instance directory layout

```
~/mojo-servers/<uuid>/
  server.jar          ← downloaded and checksum-verified server jar
  eula.txt            ← eula=true (written only after explicit user acceptance)
  server.properties   ← template with configured port
  world/              ← Minecraft world data
  world_nether/
  world_the_end/
  logs/               ← per-run timestamped log files (server-<timestamp>.log)
  plugins/            ← drop plugins here
  backups/            ← timestamped zip backups
  instance.properties ← MojoLauncher instance metadata
```

---

## Architecture notes

### ServerProcessManager lifecycle

```
start()
  └─ ProcessBuilder → java -Xms... -Xmx... -jar server.jar nogui
       ├─ stdout thread → ConsoleListener.onLine() → log file + SSE broadcast
       └─ exit-wait thread → onServerStopped() / onCrash()

stopGraceful()
  └─ stdin ← "save-all\n"
  └─ sleep 2 s
  └─ stdin ← "stop\n"
  └─ waitFor(30 s) → destroyForcibly() if timeout
```

### PaperMC API flow

```
GET https://api.papermc.io/v2/projects/paper/versions/{version}/builds
  → pick latest build number + SHA-256 hash
GET .../builds/{build}/downloads/paper-{version}-{build}.jar
  → download + SHA-256 verify
```

### HTTP API console streaming (SSE)

```
GET /api/v1/instances/{id}/console/stream
  → HTTP 200, Content-Type: text/event-stream
  → flushes last 500 buffered lines immediately
  → pushes new lines as  data: <line>\n\n
  → sends ": heartbeat\n\n" every 20 s to keep connection alive
```

---

## Security & production notes

- **Server jars**: never bundled in repo or app — always downloaded at runtime (licensing compliance).
- **EULA**: written to `eula.txt` only after explicit `acceptEula: true` in the create request (or `yes` in CLI). Cannot be bypassed.
- **Checksums**: SHA-256 (Paper) and SHA-1 (Vanilla) are verified after every download.
- **Keystores**: never commit `.jks` or `.keystore` files — use CI secrets for signing.
- **API security**: for production, add `X-API-Key` header validation. The current implementation is unauthenticated (suitable for local/trusted-network use only).
- **Memory**: validate `Xmx` against available host memory before starting. Replit free containers have limited RAM — use `Xmx=512M` for testing.

---

## Replit-specific notes

- The server-prototype runs on the Replit Linux environment with the bundled JDK.
- Building the Android APK requires the Android SDK/NDK — use the `Build APK` workflow.
- Minecraft servers use significant RAM. Use `xmx: "512M"` for testing on Replit.
- The server jar is downloaded at runtime — never bundled (licensing compliance).
- In HTTP mode on Replit, the server is accessible via the Replit public URL (the dev domain) on the configured port.
