package net.kdt.pojavlaunch.utils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.*;

public class FileUtils {
    /**
     * Check if a file denoted by a String path exists.
     * @param filePath the path to check
     * @return whether it exists (same as File.exists()
     */
    public static boolean exists(String filePath){
        return new File(filePath).exists();
    }

    /**
     * Get the file name from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the file's name
     */
    public static String getFileName(String pathOrUrl) {
        int lastSlashIndex = pathOrUrl.lastIndexOf('/');
        if(lastSlashIndex == -1) return pathOrUrl;
        return pathOrUrl.substring(lastSlashIndex);
    }

    /**
     * Remove the extension (all text after the last dot) from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the input with the extension removed
     */
    public static String removeExtension(String pathOrUrl) {
        int lastDotIndex = pathOrUrl.lastIndexOf('.');
        if(lastDotIndex == -1) return pathOrUrl;
        return pathOrUrl.substring(0, lastDotIndex);
    }

    /**
     * Ensure that a directory exists, is a directory and is writable.
     * @param targetFile the directory to check
     * @return if the check has succeeded
     */
    public static boolean ensureDirectorySilently(File targetFile) {
        if(targetFile.isFile()) return false;
        if(targetFile.exists()) return targetFile.canWrite();
        else return targetFile.mkdirs();

    }

    /**
     * Ensure that the parent directory of a file exists and is writable
     * @param targetFile the File whose parent should be checked
     * @return if the check as succeeded
     */
    public static boolean ensureParentDirectorySilently(File targetFile) {
        File parentFile = targetFile.getParentFile();
        if(parentFile == null) return false;
        return ensureDirectorySilently(parentFile);
    }

    /**
     * Same as ensureDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the directory to check
     * @throws IOException when the checks fail
     */
    public static void ensureDirectory(File targetFile) throws IOException {
        if(targetFile.isFile()) throw new IOException("Target directory is a file");
        if(targetFile.exists()) {
            if(!targetFile.canWrite()) throw new IOException("Target directory is not writable");
        }else if(!targetFile.mkdirs()) {
            // check again just in case (???)
            if(!targetFile.isDirectory()) throw new IOException("Unable to create target directory");
        }
    }

    /**
     * Same as ensureParentDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the File whose parent should be checked
     * @throws IOException when the checks fail
     */
    public static void ensureParentDirectory(File targetFile) throws IOException{
        File parentFile = targetFile.getParentFile();
        if(parentFile == null) throw new IOException("targetFile does not have a parent");
        ensureDirectory(parentFile);
    }

    /**
     * Creates a timestamped zip archive of the world folder inside an instance directory.
     *
     * The output is written to {@code <instanceDir>/backups/<timestamp>.zip}.
     * Only the {@code world/} subdirectory is zipped; all other files are ignored.
     * If no world folder exists, this method returns null without error.
     *
     * @param instanceDir root directory of the server instance
     * @return the created zip File, or null if no world folder was found
     * @throws IOException on write failure
     */
    public static File zipWorldBackup(File instanceDir) throws IOException {
        File worldDir = new File(instanceDir, "world");
        if (!worldDir.isDirectory()) return null;

        File backupsDir = new File(instanceDir, "backups");
        backupsDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File zipFile = new File(backupsDir, timestamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zipDirectory(worldDir, worldDir.getName(), zos);
        }
        return zipFile;
    }

    private static void zipDirectory(File dir, String entryPrefix, ZipOutputStream zos)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String entryName = entryPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                zipDirectory(child, entryName, zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }
}