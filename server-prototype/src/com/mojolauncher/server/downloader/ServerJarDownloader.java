package com.mojolauncher.server.downloader;

import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Downloads Minecraft server jars from PaperMC or Mojang (vanilla).
 *
 * PaperMC API docs: https://api.papermc.io/docs/swagger-ui/index.html
 * Mojang version manifest: https://launchermeta.mojang.com/mc/game/version_manifest.json
 */
public class ServerJarDownloader {

    private static final String PAPER_API = "https://api.papermc.io/v2/projects/paper";
    private static final String MOJANG_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final DownloadProgressListener listener;

    public ServerJarDownloader(DownloadProgressListener listener) {
        this.listener = listener;
    }

    public ServerJarDownloader() {
        this(new ConsoleProgressListener());
    }

    /**
     * Returns available Minecraft versions for the given server type.
     */
    public List<String> fetchAvailableVersions(ServerType type) throws IOException {
        if (type == ServerType.PAPER) {
            return fetchPaperVersions();
        } else {
            return fetchVanillaVersions();
        }
    }

    /**
     * Downloads the server jar into the instance directory.
     * Updates instance.jarFileName to the downloaded file name.
     */
    public void download(ServerInstance instance) throws IOException {
        if (instance.getServerType() == ServerType.PAPER) {
            downloadPaper(instance);
        } else {
            downloadVanilla(instance);
        }
    }

    // ── Paper ────────────────────────────────────────────────────────────────

    private List<String> fetchPaperVersions() throws IOException {
        String json = fetchUrl(PAPER_API);
        return parseJsonStringArray(json, "versions");
    }

    private void downloadPaper(ServerInstance instance) throws IOException {
        String version = instance.getMcVersion();
        String buildsUrl = PAPER_API + "/versions/" + version + "/builds";
        String buildsJson = fetchUrl(buildsUrl);

        String latestBuild = parseLatestPaperBuild(buildsJson);
        String jarName = "paper-" + version + "-" + latestBuild + ".jar";
        String downloadUrl = PAPER_API + "/versions/" + version + "/builds/" + latestBuild
                + "/downloads/" + jarName;

        String destName = "server.jar";
        File dest = new File(instance.getPath(), destName);
        downloadFile(downloadUrl, dest, jarName);

        String checksum = parseExpectedSha256(buildsJson, latestBuild, jarName);
        if (checksum != null && !checksum.isEmpty()) {
            verifyChecksum(dest, checksum);
        }

        instance.setJarFileName(destName);
        System.out.println("Downloaded Paper " + version + " build " + latestBuild + " -> " + dest);
    }

    private String parseLatestPaperBuild(String buildsJson) {
        int idx = buildsJson.lastIndexOf("\"build\":");
        if (idx < 0) throw new RuntimeException("Could not parse build number from PaperMC API response");
        int start = idx + 8;
        while (start < buildsJson.length() && !Character.isDigit(buildsJson.charAt(start))) start++;
        int end = start;
        while (end < buildsJson.length() && Character.isDigit(buildsJson.charAt(end))) end++;
        return buildsJson.substring(start, end);
    }

    private String parseExpectedSha256(String buildsJson, String build, String jarName) {
        String key = "\"sha256\":\"";
        int idx = buildsJson.lastIndexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = buildsJson.indexOf("\"", start);
        if (end < 0) return null;
        return buildsJson.substring(start, end);
    }

    // ── Vanilla ───────────────────────────────────────────────────────────────

    private List<String> fetchVanillaVersions() throws IOException {
        String json = fetchUrl(MOJANG_MANIFEST);
        List<String> versions = new ArrayList<>();
        String marker = "\"id\":\"";
        int idx = 0;
        while ((idx = json.indexOf(marker, idx)) >= 0) {
            int start = idx + marker.length();
            int end = json.indexOf("\"", start);
            if (end < 0) break;
            String ver = json.substring(start, end);
            if (ver.matches("\\d+\\.\\d+.*")) {
                versions.add(ver);
            }
            idx = end;
        }
        return versions;
    }

    private void downloadVanilla(ServerInstance instance) throws IOException {
        String version = instance.getMcVersion();
        String manifestJson = fetchUrl(MOJANG_MANIFEST);
        String versionUrl = findVersionUrl(manifestJson, version);
        if (versionUrl == null) {
            throw new IOException("Version " + version + " not found in Mojang version manifest");
        }

        String versionJson = fetchUrl(versionUrl);
        String serverUrl = parseVanillaServerUrl(versionJson);
        String sha1 = parseVanillaServerSha1(versionJson);
        if (serverUrl == null) {
            throw new IOException("No server jar found for vanilla version " + version);
        }

        String destName = "server.jar";
        File dest = new File(instance.getPath(), destName);
        downloadFile(serverUrl, dest, "vanilla-" + version + ".jar");

        if (sha1 != null && !sha1.isEmpty()) {
            verifySha1Checksum(dest, sha1);
        }

        instance.setJarFileName(destName);
        System.out.println("Downloaded Vanilla " + version + " -> " + dest);
    }

    private String findVersionUrl(String manifestJson, String version) {
        String marker = "\"id\":\"" + version + "\"";
        int idx = manifestJson.indexOf(marker);
        if (idx < 0) return null;
        String urlKey = "\"url\":\"";
        int urlIdx = manifestJson.indexOf(urlKey, idx);
        if (urlIdx < 0) return null;
        int start = urlIdx + urlKey.length();
        int end = manifestJson.indexOf("\"", start);
        return manifestJson.substring(start, end);
    }

    private String parseVanillaServerUrl(String versionJson) {
        String key = "\"server\":{\"sha1\":\"";
        int idx = versionJson.indexOf(key);
        if (idx < 0) {
            key = "\"server\":{";
            idx = versionJson.indexOf(key);
            if (idx < 0) return null;
        }
        String urlKey = "\"url\":\"";
        int urlIdx = versionJson.indexOf(urlKey, idx);
        if (urlIdx < 0) return null;
        int start = urlIdx + urlKey.length();
        int end = versionJson.indexOf("\"", start);
        return versionJson.substring(start, end);
    }

    private String parseVanillaServerSha1(String versionJson) {
        String key = "\"server\":{\"sha1\":\"";
        int idx = versionJson.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = versionJson.indexOf("\"", start);
        return versionJson.substring(start, end);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void downloadFile(String urlStr, File dest, String displayName) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "MojoLauncher-Server/1.0");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " fetching " + urlStr);

        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                downloaded += n;
                if (listener != null) listener.onProgress(displayName, downloaded, total);
            }
        }
        if (listener != null) listener.onComplete(displayName);
    }

    private String fetchUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "MojoLauncher-Server/1.0");
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " fetching " + urlStr);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        }
    }

    private List<String> parseJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String marker = "\"" + key + "\":[";
        int idx = json.indexOf(marker);
        if (idx < 0) return result;
        int start = idx + marker.length();
        int end = json.indexOf("]", start);
        if (end < 0) return result;
        String arr = json.substring(start, end);
        for (String part : arr.split(",")) {
            String val = part.trim().replace("\"", "");
            if (!val.isEmpty()) result.add(val);
        }
        Collections.reverse(result);
        return result;
    }

    private void verifyChecksum(File file, String expectedSha256) throws IOException {
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 mismatch for " + file.getName()
                    + ": expected=" + expectedSha256 + " actual=" + actual);
        }
        System.out.println("Checksum OK: " + file.getName());
    }

    private void verifySha1Checksum(File file, String expectedSha1) throws IOException {
        String actual = sha1(file);
        if (!actual.equalsIgnoreCase(expectedSha1)) {
            throw new IOException("SHA-1 mismatch for " + file.getName()
                    + ": expected=" + expectedSha1 + " actual=" + actual);
        }
        System.out.println("Checksum OK: " + file.getName());
    }

    private String sha256(File file) throws IOException {
        return digest(file, "SHA-256");
    }

    private String sha1(File file) throws IOException {
        return digest(file, "SHA-1");
    }

    private String digest(File file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
    }

    private static class ConsoleProgressListener implements DownloadProgressListener {
        private int lastPercent = -1;
        @Override
        public void onProgress(String fileName, long downloaded, long total) {
            if (total > 0) {
                int pct = (int) (downloaded * 100 / total);
                if (pct != lastPercent && pct % 10 == 0) {
                    System.out.printf("  Downloading %s: %d%%%n", fileName, pct);
                    lastPercent = pct;
                }
            }
        }
        @Override public void onComplete(String fileName) {
            System.out.println("  Download complete: " + fileName);
        }
        @Override public void onError(String fileName, Exception e) {
            System.err.println("  Download error for " + fileName + ": " + e.getMessage());
        }
    }
}
