---
name: Server prototype HTTP API
description: Embedded REST API added to server-prototype; architecture decisions and key paths.
---

The server-prototype now has two run modes controlled by `run.sh`:
- Default (no flags): interactive CLI
- `--http [--port N]`: starts embedded REST API on port 8080

**Why:** Android integration needs a stable HTTP interface; embedded `com.sun.net.httpserver` requires zero extra dependencies (JDK built-in).

**How to apply:** Any Android-side changes should talk to `GET/POST /api/v1/instances` and the `/console/stream` SSE endpoint. Never import server-prototype classes directly into the Android app.

Key files:
- `server-prototype/src/com/mojolauncher/server/api/ServerApi.java` — all route handlers
- `server-prototype/src/com/mojolauncher/server/api/JsonUtil.java` — hand-rolled JSON (no lib)
- `server-prototype/src/com/mojolauncher/server/Main.java` — `--http` flag parsed here
- `server-prototype/run.sh` — entry point with `--http` and `--port` flags

SSE console streaming: `GET /api/v1/instances/{id}/console/stream` flushes last 500 buffered lines then pushes live lines as `data: <line>\n\n`. Heartbeat every 20 s.

Version "latest" is resolved at download time by calling `fetchAvailableVersions()` and picking index 0 (newest-first ordering). `ServerInstance.setMcVersion()` was added to support this.
