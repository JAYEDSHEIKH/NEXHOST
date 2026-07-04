package com.mojolauncher.server.backup;

import com.mojolauncher.server.model.ServerInstance;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/**
 * Creates and restores zipped backups of a server's world folders.
 */
public class BackupManager {

    private static final String[] WORLD_FOLDERS = {"world", "world_nether", "world_the_end"};

    private final ServerInstance instance;

    public BackupManager(ServerInstance instance) {
        this.instance = instance;
    }

    /**
     * Creates a timestamped zip of all world folders under <instance>/backups/.
     * Returns the path to the created zip file.
     */
    public File createBackup() throws IOException {
        File backupsDir = new File(instance.getPath(), "backups");
        backupsDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File zipFile = new File(backupsDir, "backup-" + timestamp + ".zip");

        System.out.println("[Backup] Creating backup -> " + zipFile.getName());

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(zipFile)))) {

            int filesZipped = 0;
            for (String folder : WORLD_FOLDERS) {
                File worldDir = new File(instance.getPath(), folder);
                if (worldDir.isDirectory()) {
                    filesZipped += zipDirectory(worldDir, folder, zos);
                }
            }
            System.out.println("[Backup] Zipped " + filesZipped + " files -> " + zipFile.getAbsolutePath());
        }

        return zipFile;
    }

    /**
     * Restores a backup zip into the instance directory.
     * The server MUST be stopped before calling this.
     */
    public void restoreBackup(File backupZip) throws IOException {
        if (!backupZip.exists()) throw new FileNotFoundException("Backup not found: " + backupZip);

        for (String folder : WORLD_FOLDERS) {
            deleteRecursively(new File(instance.getPath(), folder));
        }

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(backupZip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File dest = new File(instance.getPath(), entry.getName());
                if (entry.isDirectory()) {
                    dest.mkdirs();
                } else {
                    dest.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) >= 0) out.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
        System.out.println("[Backup] Restored from " + backupZip.getName());
    }

    /**
     * Lists all backup files for this instance, sorted newest first.
     */
    public List<File> listBackups() {
        File backupsDir = new File(instance.getPath(), "backups");
        if (!backupsDir.isDirectory()) return Collections.emptyList();
        File[] files = backupsDir.listFiles(f -> f.getName().endsWith(".zip"));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.asList(files);
    }

    private int zipDirectory(File dir, String baseName, ZipOutputStream zos) throws IOException {
        int count = 0;
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            String entryName = baseName + "/" + file.getName();
            if (file.isDirectory()) {
                count += zipDirectory(file, entryName, zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) >= 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
