package com.mojolauncher.server.api;

import com.mojolauncher.server.backup.BackupManager;
import com.mojolauncher.server.downloader.DownloadProgressListener;
import com.mojolauncher.server.downloader.ServerJarDownloader;
import com.mojolauncher.server.lifecycle.ConsoleListener;
import com.mojolauncher.server.lifecycle.ServerProcessManager;
import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Embedded HTTP REST API for the MojoLauncher server-hosting prototype.
 *
 * Uses {@code com.sun.net.httpserver.HttpServer} — no external dependencies.
 *
 * Endpoints:
 *   GET    /api/v1/instances                        — list all instances
 *   POST   /api/v1/instances                        — create + download (async, returns 202)
 *   GET    /api/v1/instances/{id}                   — instance metadata + status
 *   POST   /api/v1/instances/{id}/start             — start server process
 *   POST   /api/v1/instances/{id}/stop              — graceful stop
 *   POST   /api/v1/instances/{id}/backup            — create world backup zip
 *   GET    /api/v1/instances/{id}/console           — last N log lines (?lines=100)
 *   GET    /api/v1/instances/{id}/console/stream    — Server-Sent Events real-time log
 *   POST   /api/v1/instances/{id}/console/command   — send command to server stdin
 *   DELETE /api/v1/instances/{id}                   — stop + delete all files
 */
public class ServerApi {

    private static final int CONSOLE_TAIL_SIZE = 500;

    private final HttpServer httpServer;
    private final String basePath;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "api-worker");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, ServerProcessManager> managers     = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>>        consoleTails = new ConcurrentHashMap<>();
    private final Map<String, List<PrintWriter>>    sseClients   = new ConcurrentHashMap<>();
    private final Map<String, String>               dlStatus     = new ConcurrentHashMap<>();

    public ServerApi(int port, String basePath) throws IOException {
        this.basePath = basePath;
        new File(basePath).mkdirs();
        httpServer = HttpServer.create(new InetSocketAddress(port), 32);
        httpServer.setExecutor(executor);
        httpServer.createContext("/api/v1/instances", this::handleInstances);
        httpServer.createContext("/",                 this::handleRoot);
    }

    public void start() {
        httpServer.start();
        System.out.println("[API] Server started  →  http://0.0.0.0:"
                + httpServer.getAddress().getPort() + "/api/v1/instances");
    }

    public void stop() {
        httpServer.stop(1);
        executor.shutdownNow();
    }

    // ── Root ─────────────────────────────────────────────────────────────────

    private void handleRoot(HttpExchange ex) throws IOException {
        respond(ex, 200, "text/plain",
                "MojoLauncher Server API\n"
                + "List instances: GET /api/v1/instances\n"
                + "Create server:  POST /api/v1/instances\n");
    }

    // ── Router ────────────────────────────────────────────────────────────────

    private void handleInstances(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path   = ex.getRequestURI().getPath();

        // strip prefix and split remaining path
        String rest = path.replaceFirst("^/api/v1/instances/?", "");
        String[] segs = rest.isEmpty() ? new String[0] : rest.split("/", -1);

        try {
            if ("OPTIONS".equals(method)) { respond(ex, 204, "text/plain", ""); return; }

            if (segs.length == 0) {
                if ("GET".equals(method))  { listInstances(ex); return; }
                if ("POST".equals(method)) { createInstance(ex); return; }
            } else if (segs.length == 1) {
                String id = segs[0];
                if ("GET".equals(method))    { getInstance(ex, id); return; }
                if ("DELETE".equals(method)) { deleteInstance(ex, id); return; }
            } else if (segs.length == 2) {
                String id = segs[0], action = segs[1];
                if ("POST".equals(method)) {
                    switch (action) {
                        case "start":  startInstance(ex, id);  return;
                        case "stop":   stopInstance(ex, id);   return;
                        case "backup": backupInstance(ex, id); return;
                    }
                }
                if ("GET".equals(method) && "console".equals(action)) {
                    getConsole(ex, id); return;
                }
            } else if (segs.length == 3 && "console".equals(segs[1])) {
                String id = segs[0], sub = segs[2];
                if ("GET".equals(method)  && "stream".equals(sub))  { streamConsole(ex, id); return; }
                if ("POST".equals(method) && "command".equals(sub)) { sendCommand(ex, id);   return; }
            }

            respond(ex, 404, "application/json", "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            try {
                respond(ex, 500, "application/json",
                        "{\"error\":" + JsonUtil.str(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}");
            } catch (Exception ignored) {}
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void listInstances(HttpExchange ex) throws IOException {
        List<ServerInstance> instances = ServerInstance.loadAll(basePath);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < instances.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJson(instances.get(i)));
        }
        sb.append("]");
        respond(ex, 200, "application/json", sb.toString());
    }

    private void createInstance(HttpExchange ex) throws IOException {
        String body       = readBody(ex);
        String name       = JsonUtil.parseString(body, "name");
        String typeStr    = JsonUtil.parseString(body, "type");
        String version    = JsonUtil.parseString(body, "version");
        String xms        = JsonUtil.parseString(body, "xms");
        String xmx        = JsonUtil.parseString(body, "xmx");
        boolean acceptEula = JsonUtil.parseBoolean(body, "acceptEula", false);

        if (name == null || name.trim().isEmpty()) {
            respond(ex, 400, "application/json", "{\"error\":\"name is required\"}"); return;
        }
        if (!acceptEula) {
            respond(ex, 400, "application/json",
                    "{\"error\":\"acceptEula must be true — explicit EULA acceptance is required\"}"); return;
        }

        ServerType type = "vanilla".equalsIgnoreCase(typeStr) ? ServerType.VANILLA : ServerType.PAPER;
        String resolvedVersion = (version == null || version.isEmpty() || "latest".equalsIgnoreCase(version))
                ? "latest" : version;

        ServerInstance inst = new ServerInstance(name.trim(), basePath, type, resolvedVersion);
        if (xms != null && !xms.isEmpty()) inst.setXms(xms);
        if (xmx != null && !xmx.isEmpty()) inst.setXmx(xmx);
        inst.setAcceptedEula(true);

        try {
            inst.initDirectories();
            inst.writeEula();
            inst.saveMeta();
        } catch (IOException e) {
            respond(ex, 500, "application/json",
                    "{\"error\":" + JsonUtil.str(e.getMessage()) + "}"); return;
        }

        String id = inst.getId();
        dlStatus.put(id, "queued");
        consoleTails.computeIfAbsent(id, k -> new ArrayDeque<>());

        executor.submit(() -> runDownload(id, type, resolvedVersion));

        respond(ex, 202, "application/json",
                "{\"id\":" + JsonUtil.str(id)
                + ",\"status\":\"queued\""
                + ",\"message\":\"Instance created — server jar download queued\"}");
    }

    private void getInstance(HttpExchange ex, String id) throws IOException {
        try {
            ServerInstance inst = ServerInstance.loadMeta(basePath + File.separator + id);
            respond(ex, 200, "application/json", toJson(inst));
        } catch (IOException e) {
            respond(ex, 404, "application/json", "{\"error\":\"Instance not found\"}");
        }
    }

    private void startInstance(HttpExchange ex, String id) throws IOException {
        ServerInstance inst;
        try {
            inst = ServerInstance.loadMeta(basePath + File.separator + id);
        } catch (IOException e) {
            respond(ex, 404, "application/json", "{\"error\":\"Instance not found\"}"); return;
        }

        ServerProcessManager existing = managers.get(id);
        if (existing != null && existing.isRunning()) {
            respond(ex, 409, "application/json", "{\"error\":\"Server already running\"}"); return;
        }

        String dl = dlStatus.get(id);
        if ("queued".equals(dl) || (dl != null && dl.startsWith("downloading"))) {
            respond(ex, 409, "application/json",
                    "{\"error\":\"Server jar still downloading — check GET /api/v1/instances/" + id + "\"}"); return;
        }

        consoleTails.computeIfAbsent(id, k -> new ArrayDeque<>());
        ServerProcessManager mgr = new ServerProcessManager(inst);
        mgr.addListener(makeListener(id));
        managers.put(id, mgr);

        try {
            mgr.start();
        } catch (IOException e) {
            managers.remove(id);
            respond(ex, 500, "application/json",
                    "{\"error\":" + JsonUtil.str(e.getMessage()) + "}"); return;
        }

        respond(ex, 200, "application/json", "{\"status\":\"starting\"}");
    }

    private void stopInstance(HttpExchange ex, String id) throws IOException {
        ServerProcessManager mgr = managers.get(id);
        if (mgr == null || !mgr.isRunning()) {
            respond(ex, 409, "application/json", "{\"error\":\"Server is not running\"}"); return;
        }
        executor.submit(() -> {
            try { mgr.stopGraceful(); } catch (Exception e) { mgr.forceStop(); }
        });
        respond(ex, 200, "application/json", "{\"status\":\"stopping\"}");
    }

    private void backupInstance(HttpExchange ex, String id) throws IOException {
        ServerInstance inst;
        try {
            inst = ServerInstance.loadMeta(basePath + File.separator + id);
        } catch (IOException e) {
            respond(ex, 404, "application/json", "{\"error\":\"Instance not found\"}"); return;
        }
        ServerProcessManager mgr = managers.get(id);
        if (mgr != null && mgr.isRunning()) {
            try { mgr.sendCommand("save-all"); Thread.sleep(2000); }
            catch (Exception ignored) {}
        }
        try {
            File zip = new BackupManager(inst).createBackup();
            respond(ex, 200, "application/json",
                    "{\"backup\":" + JsonUtil.str(zip.getName())
                    + ",\"path\":" + JsonUtil.str(zip.getAbsolutePath()) + "}");
        } catch (IOException e) {
            respond(ex, 500, "application/json",
                    "{\"error\":" + JsonUtil.str(e.getMessage()) + "}");
        }
    }

    private void getConsole(HttpExchange ex, String id) throws IOException {
        int lines = 100;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("lines=")) {
                    try { lines = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
        }

        Deque<String> tail = consoleTails.getOrDefault(id, new ArrayDeque<>());
        List<String> recent;
        synchronized (tail) {
            List<String> all = new ArrayList<>(tail);
            int start = Math.max(0, all.size() - lines);
            recent = all.subList(start, all.size());
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(JsonUtil.str(recent.get(i)));
        }
        sb.append("]");
        respond(ex, 200, "application/json", sb.toString());
    }

    private void streamConsole(HttpExchange ex, String id) throws IOException {
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0); // 0 = chunked / unknown length

        PrintWriter writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8)));

        // flush existing tail immediately
        Deque<String> tail = consoleTails.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (tail) {
            for (String line : tail) {
                writer.print("data: " + sseEscape(line) + "\n\n");
            }
        }
        writer.flush();

        // register this client for live pushes
        sseClients.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(writer);

        // hold the connection open; send periodic heartbeats
        try {
            while (!writer.checkError()) {
                writer.print(": heartbeat\n\n");
                writer.flush();
                Thread.sleep(20_000);
            }
        } catch (InterruptedException ignored) {
        } finally {
            List<PrintWriter> clients = sseClients.get(id);
            if (clients != null) clients.remove(writer);
            try { ex.getResponseBody().close(); } catch (IOException ignored) {}
        }
    }

    private void sendCommand(HttpExchange ex, String id) throws IOException {
        ServerProcessManager mgr = managers.get(id);
        if (mgr == null || !mgr.isRunning()) {
            respond(ex, 409, "application/json", "{\"error\":\"Server is not running\"}"); return;
        }
        String body    = readBody(ex);
        String command = JsonUtil.parseString(body, "command");
        if (command == null || command.trim().isEmpty()) {
            respond(ex, 400, "application/json", "{\"error\":\"command field is required\"}"); return;
        }
        mgr.sendCommand(command.trim());
        respond(ex, 200, "application/json", "{\"sent\":true}");
    }

    private void deleteInstance(HttpExchange ex, String id) throws IOException {
        ServerProcessManager mgr = managers.get(id);
        if (mgr != null && mgr.isRunning()) {
            respond(ex, 409, "application/json",
                    "{\"error\":\"Stop the server before deleting\"}"); return;
        }
        File instDir = new File(basePath, id);
        if (!instDir.isDirectory()) {
            respond(ex, 404, "application/json", "{\"error\":\"Instance not found\"}"); return;
        }
        if (mgr != null) {
            mgr.shutdown();
            managers.remove(id);
        }
        deleteRecursively(instDir);
        consoleTails.remove(id);
        sseClients.remove(id);
        dlStatus.remove(id);
        respond(ex, 200, "application/json", "{\"deleted\":true}");
    }

    // ── Download worker ───────────────────────────────────────────────────────

    private void runDownload(String id, ServerType type, String requestedVersion) {
        ServerInstance inst;
        try {
            inst = ServerInstance.loadMeta(basePath + File.separator + id);
        } catch (IOException e) {
            dlStatus.put(id, "error: cannot load instance — " + e.getMessage()); return;
        }

        try {
            ServerJarDownloader dl = new ServerJarDownloader(new DownloadProgressListener() {
                @Override public void onProgress(String file, long downloaded, long total) {
                    String pct = total > 0 ? (downloaded * 100 / total) + "%" : downloaded + " bytes";
                    dlStatus.put(id, "downloading: " + pct);
                    broadcast(id, "[Download] " + file + " — " + pct);
                }
                @Override public void onComplete(String file) {
                    dlStatus.put(id, "ready");
                    broadcast(id, "[Download] Complete: " + file);
                }
                @Override public void onError(String file, Exception e) {
                    dlStatus.put(id, "error: " + e.getMessage());
                    broadcast(id, "[Download] Error: " + e.getMessage());
                }
            });

            // resolve "latest" to a real version number
            if ("latest".equalsIgnoreCase(requestedVersion)) {
                broadcast(id, "[API] Resolving latest version for " + type.displayName + "...");
                List<String> versions = dl.fetchAvailableVersions(type);
                if (versions.isEmpty()) throw new IOException("No versions available for " + type.displayName);
                String resolved = versions.get(0);
                inst.setMcVersion(resolved);
                inst.saveMeta();
                broadcast(id, "[API] Latest version resolved: " + resolved);
            }

            dlStatus.put(id, "downloading");
            dl.download(inst);
            inst.saveMeta();
            dlStatus.put(id, "ready");
            broadcast(id, "[API] Ready — POST /api/v1/instances/" + id + "/start to launch");

        } catch (Exception e) {
            dlStatus.put(id, "error: " + e.getMessage());
            broadcast(id, "[API] Download failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ConsoleListener makeListener(String id) {
        return new ConsoleListener() {
            @Override public void onLine(String line) { broadcast(id, line); }
            @Override public void onServerStarted()    { broadcast(id, "*** Server is ready! ***"); }
            @Override public void onServerStopped(int code) {
                broadcast(id, "*** Server stopped (exit " + code + ") ***");
            }
            @Override public void onCrash(int code, String tail) {
                broadcast(id, "*** Server CRASHED (exit " + code + ") ***");
                if (tail != null && !tail.isEmpty()) broadcast(id, tail);
            }
        };
    }

    private void broadcast(String id, String line) {
        Deque<String> tail = consoleTails.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (tail) {
            if (tail.size() >= CONSOLE_TAIL_SIZE) tail.pollFirst();
            tail.addLast(line);
        }
        List<PrintWriter> clients = sseClients.get(id);
        if (clients == null || clients.isEmpty()) return;
        String sseData = "data: " + sseEscape(line) + "\n\n";
        List<PrintWriter> stale = new ArrayList<>();
        for (PrintWriter pw : clients) {
            pw.print(sseData);
            pw.flush();
            if (pw.checkError()) stale.add(pw);
        }
        if (!stale.isEmpty()) clients.removeAll(stale);
    }

    private static String sseEscape(String line) {
        // SSE data lines must not contain raw newlines; replace with space
        return line == null ? "" : line.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }

    private String toJson(ServerInstance inst) {
        ServerProcessManager mgr = managers.get(inst.getId());
        boolean running = mgr != null && mgr.isRunning();
        String status = running ? inst.getStatus().name() : inst.getStatus().name();
        if (running && inst.getStatus() == ServerInstance.Status.STOPPED) status = "RUNNING";
        String dl = dlStatus.get(inst.getId());
        if (dl != null && !"ready".equals(dl)) status = dl;

        return "{"
                + "\"id\":"          + JsonUtil.str(inst.getId())                + ","
                + "\"name\":"        + JsonUtil.str(inst.getName())              + ","
                + "\"type\":"        + JsonUtil.str(inst.getServerType().name().toLowerCase()) + ","
                + "\"version\":"     + JsonUtil.str(inst.getMcVersion())         + ","
                + "\"status\":"      + JsonUtil.str(status)                      + ","
                + "\"port\":"        + inst.getPort()                            + ","
                + "\"xms\":"         + JsonUtil.str(inst.getXms())               + ","
                + "\"xmx\":"         + JsonUtil.str(inst.getXmx())               + ","
                + "\"acceptedEula\":" + inst.isAcceptedEula()                    + ","
                + "\"createdAt\":"   + inst.getCreatedAt()                       + ","
                + "\"path\":"        + JsonUtil.str(inst.getPath())
                + "}";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        }
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",  contentType + "; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-API-Key");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
