package com.mojolauncher.server;

import com.mojolauncher.server.lifecycle.ConsoleListener;
import com.mojolauncher.server.lifecycle.ServerProcessManager;
import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for ServerProcessManager using the FakeServer jar.
 *
 * Requirements:
 *   fake-server.jar must be present in the working directory (built by build.sh).
 *
 * Run via:
 *   java -ea -cp "out:test-out" com.mojolauncher.server.ServerLifecycleTest
 */
public class ServerLifecycleTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Generous timeout so CI on slow machines doesn't flake. */
    private static final long TIMEOUT_MS = 10_000;

    public static void main(String[] args) throws Exception {
        File fakeJar = new File("fake-server.jar");
        if (!fakeJar.exists()) {
            System.err.println("[FATAL] fake-server.jar not found — run ./build.sh first.");
            System.exit(1);
        }

        File tmpBase = Files.createTempDirectory("mojo-lifecycle-test").toFile();
        try {
            testStartAndReady(tmpBase, fakeJar);
            testSendCommand(tmpBase, fakeJar);
            testGracefulStop(tmpBase, fakeJar);
            testCrashDetection(tmpBase, fakeJar);
            testShutdown(tmpBase, fakeJar);
        } finally {
            deleteRecursively(tmpBase);
        }

        System.out.printf("%n=== Results: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /** Server starts and the "Done" banner triggers onServerStarted(). */
    static void testStartAndReady(File base, File fakeJar) throws Exception {
        ServerInstance inst = makeInstance("start-ready", base, fakeJar);
        ServerProcessManager mgr = new ServerProcessManager(inst);

        CountDownLatch readyLatch = new CountDownLatch(1);
        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted()              { readyLatch.countDown(); }
            @Override public void onServerStopped(int exitCode)  {}
            @Override public void onCrash(int exitCode, String lastLines) {}
        });

        mgr.start();
        boolean ready = readyLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("onServerStarted fires after 'Done' line", ready);
        assertThat("isRunning() true after start",            mgr.isRunning());

        mgr.stopGraceful();
        mgr.shutdown();
    }

    /** Commands sent to stdin produce console output visible via listeners. */
    static void testSendCommand(File base, File fakeJar) throws Exception {
        ServerInstance inst = makeInstance("send-cmd", base, fakeJar);
        ServerProcessManager mgr = new ServerProcessManager(inst);

        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch listLatch  = new CountDownLatch(1);
        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) {
                if (line.contains("players online")) listLatch.countDown();
            }
            @Override public void onServerStarted()              { readyLatch.countDown(); }
            @Override public void onServerStopped(int exitCode)  {}
            @Override public void onCrash(int exitCode, String lastLines) {}
        });

        mgr.start();
        assertThat("server ready before command",
                readyLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mgr.sendCommand("list");
        assertThat("console receives response to 'list'",
                listLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mgr.stopGraceful();
        mgr.shutdown();
    }

    /** stopGraceful() sends save-all + stop; exit code is 0 and isRunning() becomes false. */
    static void testGracefulStop(File base, File fakeJar) throws Exception {
        ServerInstance inst = makeInstance("graceful-stop", base, fakeJar);
        ServerProcessManager mgr = new ServerProcessManager(inst);

        CountDownLatch readyLatch   = new CountDownLatch(1);
        CountDownLatch stoppedLatch = new CountDownLatch(1);
        AtomicInteger  exitCode     = new AtomicInteger(-99);

        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted()              { readyLatch.countDown(); }
            @Override public void onServerStopped(int code)  { exitCode.set(code); stoppedLatch.countDown(); }
            @Override public void onCrash(int code, String lastLines) { exitCode.set(code); stoppedLatch.countDown(); }
        });

        mgr.start();
        assertThat("server ready", readyLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mgr.stopGraceful();
        assertThat("onServerStopped fires",       stoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThat("exit code is 0 (clean stop)", exitCode.get() == 0);
        assertThat("isRunning() false after stop",!mgr.isRunning());

        mgr.shutdown();
    }

    /**
     * A process that exits non-zero before printing "Done" triggers onCrash().
     * FakeServer exits with code 1 when it finds a ".fake-crash" file in its
     * working directory (= the instance directory).
     */
    static void testCrashDetection(File base, File fakeJar) throws Exception {
        ServerInstance inst = makeInstance("crash-detect", base, fakeJar);

        // Plant the trigger file in the instance directory before starting
        new File(inst.getPath(), ".fake-crash").createNewFile();

        ServerProcessManager mgr = new ServerProcessManager(inst);

        CountDownLatch crashLatch = new CountDownLatch(1);
        AtomicInteger  crashCode  = new AtomicInteger(0);

        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted()              {}
            @Override public void onServerStopped(int exitCode)  {}
            @Override public void onCrash(int exitCode, String lastLines) {
                crashCode.set(exitCode);
                crashLatch.countDown();
            }
        });

        mgr.start();
        assertThat("onCrash fires for non-zero exit",   crashLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThat("crash exit code is non-zero",        crashCode.get() != 0);
        assertThat("isRunning() false after crash",      !mgr.isRunning());

        mgr.shutdown();
    }

    /** shutdown() terminates the executor cleanly after the server has stopped. */
    static void testShutdown(File base, File fakeJar) throws Exception {
        ServerInstance inst = makeInstance("shutdown", base, fakeJar);
        ServerProcessManager mgr = new ServerProcessManager(inst);

        CountDownLatch readyLatch = new CountDownLatch(1);
        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted()              { readyLatch.countDown(); }
            @Override public void onServerStopped(int exitCode)  {}
            @Override public void onCrash(int exitCode, String lastLines) {}
        });

        mgr.start();
        readyLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        mgr.stopGraceful();
        mgr.shutdown(); // must not throw or deadlock
        assertThat("shutdown() completes cleanly", true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a fully initialised ServerInstance backed by FakeServer,
     * with eula.txt written (EULA pre-accepted — test-only).
     */
    private static ServerInstance makeInstance(String label, File base, File fakeJar)
            throws IOException {
        ServerInstance inst = new ServerInstance(
                label + "-" + System.nanoTime(),
                base.getAbsolutePath(),
                ServerType.PAPER,
                "FAKE");
        inst.setAcceptedEula(true);
        inst.initDirectories();
        inst.writeEula();

        // Copy fake-server.jar into the instance directory as "server.jar"
        Files.copy(fakeJar.toPath(),
                   new File(inst.getPath(), inst.getJarFileName()).toPath(),
                   StandardCopyOption.REPLACE_EXISTING);
        return inst;
    }

    private static void assertThat(String description, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + description);
            passed++;
        } else {
            System.out.println("  FAIL: " + description);
            failed++;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
