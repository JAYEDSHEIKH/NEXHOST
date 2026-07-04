package net.kdt.pojavlaunch.lifecycle;

import net.kdt.pojavlaunch.instances.ServerInstance;
import net.kdt.pojavlaunch.instances.ServerInstance.Status;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the full lifecycle of a Minecraft server process:
 *   start -> stream console -> stop (graceful or forced) -> crash detection.
 *
 * Call addConsoleListener() to receive real-time log lines and lifecycle events
 * (works on Android via Handler-posted callbacks or directly on background threads).
 */
public class ServerProcessManager {

    public interface ConsoleListener {
        void onLine(String line);
        void onServerStarted();
        void onServerStopped(int exitCode);
        void onCrash(int exitCode, String lastLines);
    }

    private static final long GRACEFUL_STOP_TIMEOUT_MS = 30_000;
    private static final int TAIL_LINES = 20;

    private final ServerInstance instance;
    private final List<ConsoleListener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<String> consoleTail = new ArrayDeque<>(TAIL_LINES + 1);
    private Process process;
    private Thread stdoutThread;
    private BufferedWriter logWriter;
    private final AtomicBoolean stoppingIntentionally = new AtomicBoolean(false);
    private final String javaOverride; // null means auto-detect via javaPath()

    /** Constructor for plain-Java (server-prototype) use — auto-detects java binary. */
    public ServerProcessManager(ServerInstance instance) {
        this(instance, null);
    }

    /**
     * Constructor that accepts an explicit java binary path.
     * Use this on Android to pass the bundled JRE binary resolved by ServerForegroundService.
     *
     * @param instance    the server instance to manage
     * @param javaOverride absolute path to java binary, or null to auto-detect
     */
    public ServerProcessManager(ServerInstance instance, String javaOverride) {
        this.instance = instance;
        this.javaOverride = javaOverride;
    }

    public void addConsoleListener(ConsoleListener l) { listeners.add(l); }
    public void removeConsoleListener(ConsoleListener l) { listeners.remove(l); }

    /** Returns the server instance this manager is responsible for. */
    public ServerInstance getServerInstance() { return instance; }

    /** Starts the server process. Non-blocking — output is streamed asynchronously. */
    public synchronized void start() throws IOException {
        if (process != null && process.isAlive())
            throw new IllegalStateException("Server already running");

        File dir = new File(instance.getPath());
        if (!dir.isDirectory()) throw new IOException("Instance dir missing: " + dir);
        if (!new File(dir, instance.getJarFileName()).exists())
            throw new FileNotFoundException("server.jar not found in " + dir);
        if (!new File(dir, "eula.txt").exists())
            throw new IOException("eula.txt missing — user must accept the EULA first");

        stoppingIntentionally.set(false);
        instance.setStatus(Status.STARTING);

        ProcessBuilder pb = new ProcessBuilder(buildCommand());
        pb.directory(dir);
        pb.redirectErrorStream(true);

        try {
            openLogWriter();
            process = pb.start();
        } catch (IOException e) {
            instance.setStatus(Status.STOPPED);
            closeLogWriter();
            throw e;
        }

        stdoutThread = new Thread(this::streamOutput, "srv-out-" + instance.getId());
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        new Thread(this::awaitExit, "srv-exit-" + instance.getId()).start();
    }

    /** Sends a command string to the server stdin (e.g. "list", "stop"). */
    public synchronized void sendCommand(String cmd) throws IOException {
        if (process == null || !process.isAlive())
            throw new IllegalStateException("Server is not running");
        OutputStream os = process.getOutputStream();
        os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /** Gracefully stops: save-all → stop → wait → force kill if needed. */
    public synchronized void stopGraceful() throws IOException, InterruptedException {
        if (process == null || !process.isAlive()) return;
        instance.setStatus(Status.STOPPING);
        stoppingIntentionally.set(true);
        broadcast("[Manager] Sending save-all...");
        sendCommand("save-all");
        Thread.sleep(2000);
        broadcast("[Manager] Sending stop...");
        sendCommand("stop");
        if (!process.waitFor(GRACEFUL_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            broadcast("[Manager] Graceful stop timed out — force killing.");
            process.destroyForcibly();
        }
    }

    /** Force-kills the server process immediately. */
    public synchronized void forceStop() {
        stoppingIntentionally.set(true);
        if (process != null) process.destroyForcibly();
    }

    public boolean isRunning() { return process != null && process.isAlive(); }

    /**
     * Normalises a JVM memory value: appends "M" if the value is all digits and has no unit suffix.
     */
    private static String normaliseMemory(String value) {
        if (value != null && value.matches("\\d+")) return value + "M";
        return value;
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaOverride != null ? javaOverride : javaPath());
        cmd.add("-Xms" + normaliseMemory(instance.getXms()));
        cmd.add("-Xmx" + normaliseMemory(instance.getXmx()));
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+ParallelRefProcEnabled");
        cmd.add("-XX:MaxGCPauseMillis=200");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(instance.getJarFileName());
        cmd.add("nogui");
        return cmd;
    }

    private void streamOutput() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                writeToLog(line);
                synchronized (consoleTail) {
                    if (consoleTail.size() >= TAIL_LINES) consoleTail.pollFirst();
                    consoleTail.addLast(line);
                }
                broadcast(line);
                if (instance.getStatus() == Status.STARTING && isDoneLine(line)) {
                    instance.setStatus(Status.RUNNING);
                    for (ConsoleListener l : listeners) {
                        try { l.onServerStarted(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            if (process != null && process.isAlive()) broadcast("[Manager] Console read error: " + e.getMessage());
        }
    }

    private void awaitExit() {
        try {
            int code = process.waitFor();
            closeLogWriter();
            instance.setStatus(Status.STOPPED);
            if (!stoppingIntentionally.get() && code != 0) {
                instance.setStatus(Status.CRASHED);
                String tail;
                synchronized (consoleTail) { tail = String.join("\n", consoleTail); }
                for (ConsoleListener l : listeners) {
                    try { l.onCrash(code, tail); } catch (Exception ignored) {}
                }
            } else {
                for (ConsoleListener l : listeners) {
                    try { l.onServerStopped(code); } catch (Exception ignored) {}
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean isDoneLine(String line) {
        return line.contains("Done (") && line.contains("For help, type");
    }

    private void broadcast(String line) {
        for (ConsoleListener l : listeners) {
            try { l.onLine(line); } catch (Exception ignored) {}
        }
    }

    private void openLogWriter() {
        try {
            File logsDir = new File(instance.getPath(), "logs");
            logsDir.mkdirs();
            String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            synchronized (this) {
                logWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(logsDir, "server-" + ts + ".log"), true),
                        StandardCharsets.UTF_8));
            }
        } catch (IOException e) { /* logging is best-effort */ }
    }

    private void writeToLog(String line) {
        synchronized (this) {
            if (logWriter == null) return;
            try { logWriter.write(line); logWriter.newLine(); logWriter.flush(); }
            catch (IOException ignored) {}
        }
    }

    private void closeLogWriter() {
        synchronized (this) {
            if (logWriter == null) return;
            try { logWriter.close(); } catch (IOException ignored) {}
            logWriter = null;
        }
    }

    private static String javaPath() {
        String jh = System.getProperty("java.home");
        if (jh != null) {
            File j = new File(jh, "bin/java");
            if (j.exists()) return j.getAbsolutePath();
        }
        return "java";
    }
}
