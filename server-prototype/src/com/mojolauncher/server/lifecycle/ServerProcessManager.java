package com.mojolauncher.server.lifecycle;

import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerInstance.Status;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of a single Minecraft server process:
 *   start -> stream console -> stop (graceful or forced) -> crash detection
 */
public class ServerProcessManager {

    private static final long GRACEFUL_STOP_TIMEOUT_MS = 30_000;
    private static final int CRASH_TAIL_LINES = 20;

    private final ServerInstance instance;
    private final List<ConsoleListener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<String> consoleTail = new ArrayDeque<>(CRASH_TAIL_LINES + 1);

    private Process process;
    private Thread stdoutThread;
    private BufferedWriter logWriter;
    private final AtomicBoolean stoppingIntentionally = new AtomicBoolean(false);
    private final ExecutorService executor;

    public ServerProcessManager(ServerInstance instance) {
        this.instance = instance;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "server-mgr-" + instance.getId());
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(ConsoleListener l) { listeners.add(l); }
    public void removeListener(ConsoleListener l) { listeners.remove(l); }

    /**
     * Starts the Minecraft server process. Non-blocking; console is streamed async.
     */
    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Server is already running");
        }

        File instanceDir = new File(instance.getPath());
        if (!instanceDir.isDirectory()) {
            throw new IOException("Instance directory does not exist: " + instanceDir);
        }

        File jarFile = new File(instanceDir, instance.getJarFileName());
        if (!jarFile.exists()) {
            throw new FileNotFoundException("Server jar not found: " + jarFile);
        }

        File eulaFile = new File(instanceDir, "eula.txt");
        if (!eulaFile.exists()) {
            throw new IOException("eula.txt is missing. Accept the EULA before starting.");
        }

        String javaPath = findJava();
        List<String> cmd = buildCommand(javaPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(instanceDir);
        pb.redirectErrorStream(true);

        stoppingIntentionally.set(false);
        instance.setStatus(Status.STARTING);

        try {
            openLogWriter();
            process = pb.start();
        } catch (IOException e) {
            instance.setStatus(Status.STOPPED);
            closeLogWriter();
            throw e;
        }

        stdoutThread = new Thread(this::streamOutput, "server-stdout-" + instance.getId());
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        executor.submit(this::waitForExit);
        System.out.println("[Manager] Started server process (PID via handle): " + instance.getName());
    }

    /**
     * Sends a command string to the server's stdin (e.g., "list", "stop").
     */
    public synchronized void sendCommand(String command) throws IOException {
        if (process == null || !process.isAlive()) {
            throw new IllegalStateException("Server is not running");
        }
        OutputStream os = process.getOutputStream();
        os.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * Gracefully stops the server: save-all -> stop -> wait -> force kill.
     */
    public synchronized void stopGraceful() throws IOException, InterruptedException {
        if (process == null || !process.isAlive()) return;
        instance.setStatus(Status.STOPPING);
        stoppingIntentionally.set(true);
        broadcastLine("[Manager] Sending save-all ...");
        sendCommand("save-all");
        Thread.sleep(2000);
        broadcastLine("[Manager] Sending stop ...");
        sendCommand("stop");
        boolean exited = process.waitFor(GRACEFUL_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!exited) {
            broadcastLine("[Manager] Graceful stop timed out — force killing.");
            process.destroyForcibly();
        }
    }

    /**
     * Force-kills the server process immediately.
     */
    public synchronized void forceStop() {
        if (process != null) process.destroyForcibly();
        stoppingIntentionally.set(true);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Releases resources held by this manager. Call this when the instance is
     * permanently deleted or the application is shutting down. Safe to call
     * multiple times; any running process is force-killed first.
     */
    public void shutdown() {
        forceStop();
        executor.shutdownNow();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Normalises a JVM memory value: if it is all digits (no unit suffix) append "M".
     * Silently accepts values that already carry a unit (M, G, K, etc.).
     */
    private static String normaliseMemory(String value) {
        if (value != null && value.matches("\\d+")) return value + "M";
        return value;
    }

    private List<String> buildCommand(String javaPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xms" + normaliseMemory(instance.getXms()));
        cmd.add("-Xmx" + normaliseMemory(instance.getXmx()));
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+ParallelRefProcEnabled");
        cmd.add("-XX:MaxGCPauseMillis=200");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(instance.getJarFileName());
        cmd.add("nogui");
        System.out.println("[Manager] Command: " + String.join(" ", cmd));
        return cmd;
    }

    private void streamOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException e) {
            if (process != null && process.isAlive()) {
                broadcastLine("[Manager] Console read error: " + e.getMessage());
            }
        }
    }

    private void handleLine(String line) {
        broadcastLine(line);
        writeToLog(line);

        synchronized (consoleTail) {
            if (consoleTail.size() >= CRASH_TAIL_LINES) consoleTail.pollFirst();
            consoleTail.addLast(line);
        }

        if (instance.getStatus() == Status.STARTING && isStartedLine(line)) {
            instance.setStatus(Status.RUNNING);
            for (ConsoleListener l : listeners) {
                try { l.onServerStarted(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isStartedLine(String line) {
        return line.contains("Done (") && line.contains("For help, type");
    }

    private void waitForExit() {
        try {
            int code = process.waitFor();
            closeLogWriter();
            instance.setStatus(Status.STOPPED);

            if (!stoppingIntentionally.get() && code != 0) {
                String tail = buildTail();
                instance.setStatus(Status.CRASHED);
                for (ConsoleListener l : listeners) {
                    try { l.onCrash(code, tail); } catch (Exception ignored) {}
                }
                broadcastLine("[Manager] Server CRASHED with exit code " + code);
            } else {
                for (ConsoleListener l : listeners) {
                    try { l.onServerStopped(code); } catch (Exception ignored) {}
                }
                broadcastLine("[Manager] Server stopped (exit code " + code + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildTail() {
        synchronized (consoleTail) {
            return String.join("\n", consoleTail);
        }
    }

    private void broadcastLine(String line) {
        for (ConsoleListener l : listeners) {
            try { l.onLine(line); } catch (Exception ignored) {}
        }
    }

    private void openLogWriter() {
        try {
            File logsDir = new File(instance.getPath(), "logs");
            logsDir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(logsDir, "server-" + timestamp + ".log");
            synchronized (this) {
                logWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("[Manager] Could not open log file: " + e.getMessage());
        }
    }

    private void writeToLog(String line) {
        synchronized (this) {
            if (logWriter == null) return;
            try {
                logWriter.write(line);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    private void closeLogWriter() {
        synchronized (this) {
            if (logWriter != null) {
                try { logWriter.close(); } catch (IOException ignored) {}
                logWriter = null;
            }
        }
    }

    private static String findJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File candidate = new File(javaHome, "bin/java");
            if (candidate.exists()) return candidate.getAbsolutePath();
        }
        return "java";
    }
}
