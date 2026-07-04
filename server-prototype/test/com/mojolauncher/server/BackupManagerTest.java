package com.mojolauncher.server;

import com.mojolauncher.server.backup.BackupManager;
import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * Integration test: verifies backup creation and restore.
 * Run with: java -cp out com.mojolauncher.server.BackupManagerTest
 */
public class BackupManagerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File tmpBase = Files.createTempDirectory("mojo-backup-test").toFile();
        try {
            testCreateBackup(tmpBase);
            testRestoreBackup(tmpBase);
            testListBackups(tmpBase);
        } finally {
            deleteRecursively(tmpBase);
        }
        System.out.printf("%n=== Results: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void testCreateBackup(File base) throws Exception {
        System.out.print("testCreateBackup ... ");
        ServerInstance inst = makeInstance(base);

        File worldDir = new File(inst.getPath(), "world");
        worldDir.mkdirs();
        Files.write(Paths.get(inst.getPath(), "world", "level.dat"), "fake-nbt-data".getBytes());
        new File(inst.getPath(), "world/region").mkdirs();
        Files.write(Paths.get(inst.getPath(), "world/region", "r.0.0.mca"), "fake-chunk".getBytes());

        BackupManager bm = new BackupManager(inst);
        File zip = bm.createBackup();
        assert zip.exists() : "Backup zip not created";
        assert zip.length() > 0 : "Backup zip is empty";
        assert zip.getName().endsWith(".zip") : "Backup file should be a .zip";
        pass();
    }

    static void testRestoreBackup(File base) throws Exception {
        System.out.print("testRestoreBackup ... ");
        ServerInstance inst = makeInstance(base);

        File worldDir = new File(inst.getPath(), "world");
        worldDir.mkdirs();
        Files.write(Paths.get(inst.getPath(), "world", "original.dat"), "original".getBytes());

        BackupManager bm = new BackupManager(inst);
        File zip = bm.createBackup();

        Files.write(Paths.get(inst.getPath(), "world", "original.dat"), "modified".getBytes());

        bm.restoreBackup(zip);

        String content = new String(Files.readAllBytes(Paths.get(inst.getPath(), "world", "original.dat")));
        assert "original".equals(content) : "Restore did not recover original file content, got: " + content;
        pass();
    }

    static void testListBackups(File base) throws Exception {
        System.out.print("testListBackups ... ");
        ServerInstance inst = makeInstance(base);
        File worldDir = new File(inst.getPath(), "world");
        worldDir.mkdirs();
        Files.write(Paths.get(inst.getPath(), "world", "x.dat"), "x".getBytes());

        BackupManager bm = new BackupManager(inst);
        bm.createBackup();
        Thread.sleep(1100);
        bm.createBackup();

        List<File> backups = bm.listBackups();
        assert backups.size() >= 2 : "Expected at least 2 backups, got " + backups.size();
        assert backups.get(0).lastModified() >= backups.get(1).lastModified() : "Backups not sorted newest first";
        pass();
    }

    private static ServerInstance makeInstance(File base) throws IOException {
        ServerInstance inst = new ServerInstance("backup-test-" + System.nanoTime(),
                base.getAbsolutePath(), ServerType.PAPER, "1.21.4");
        inst.setAcceptedEula(true);
        inst.initDirectories();
        return inst;
    }

    private static void pass() { System.out.println("PASS"); passed++; }
    private static void fail(String msg) { System.out.println("FAIL: " + msg); failed++; }
    private static void deleteRecursively(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteRecursively(c);
        f.delete();
    }
}
