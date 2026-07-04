package net.kdt.pojavlaunch.downloader;

import net.kdt.pojavlaunch.instances.ServerInstance;
import net.kdt.pojavlaunch.instances.ServerInstance.ServerType;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/**
 * Downloads Minecraft server jars from PaperMC or Mojang (vanilla).
 *
 * PaperMC API: https://api.papermc.io/v2/projects/paper
 * Mojang manifest: https://launchermeta.mojang.com/mc/game/version_manifest.json
 */
public class ServerJarDownloader {

    private static final String PAPER_API = "https://api.papermc.io/v2/projects/paper";
    private static final String MOJANG_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
        void onComplete();
    }

    private final ProgressCallback callback;

    public ServerJarDownloader(ProgressCallback callback) {
        this.callback = callback;
    }

    public List<String> fetchAvailableVersions(ServerType type) throws IOException {
        if (type == ServerType.PAPER) return fetchPaperVersions();
        return fetchVanillaVersions();
    }

    public void download(ServerInstance instance) throws IOException {
        if (instance.getServerType() == ServerType.PAPER) downloadPaper(instance);
        else downloadVanilla(instance);
    }

    private List<String> fetchPaperVersions() throws IOException {
        String json = fetchUrl(PAPER_API);
        return parseStringArray(json, "versions");
    }

    private void downloadPaper(ServerInstance instance) throws IOException {
        String version = instance.getMcVersion();
        String buildsJson = fetchUrl(PAPER_API + "/versions/" + version + "/builds");
        String build = parseLatestBuild(buildsJson);
        String jarName = "paper-" + version + "-" + build + ".jar";
        String url = PAPER_API + "/versions/" + version + "/builds/" + build + "/downloads/" + jarName;
        File dest = new File(instance.getPath(), "server.jar");
        downloadToFile(url, dest);
        String sha256 = parseSha256(buildsJson);
        if (sha256 != null) verifySha256(dest, sha256);
        instance.setJarFileName("server.jar");
    }

    private List<String> fetchVanillaVersions() throws IOException {
        String json = fetchUrl(MOJANG_MANIFEST);
        List<String> versions = new ArrayList<>();
        String marker = "\"id\":\"";
        int idx = 0;
        while ((idx = json.indexOf(marker, idx)) >= 0) {
            int s = idx + marker.length(), e = json.indexOf("\"", s);
            if (e < 0) break;
            String v = json.substring(s, e);
            if (v.matches("\\d+\\.\\d+.*")) versions.add(v);
            idx = e;
        }
        return versions;
    }

    private void downloadVanilla(ServerInstance instance) throws IOException {
        String version = instance.getMcVersion();
        String manifest = fetchUrl(MOJANG_MANIFEST);
        String versionUrl = findVersionUrl(manifest, version);
        if (versionUrl == null) throw new IOException("Version not found: " + version);
        String versionJson = fetchUrl(versionUrl);
        String serverUrl = parseVanillaServerUrl(versionJson);
        String sha1 = parseVanillaSha1(versionJson);
        if (serverUrl == null) throw new IOException("No server jar for version: " + version);
        File dest = new File(instance.getPath(), "server.jar");
        downloadToFile(serverUrl, dest);
        if (sha1 != null) verifySha1(dest, sha1);
        instance.setJarFileName("server.jar");
    }

    private void downloadToFile(String urlStr, File dest) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "MojoLauncher/1.0");
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode() + " for " + urlStr);
        }
        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long downloaded = 0; int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                downloaded += n;
                if (callback != null) callback.onProgress(downloaded, total);
            }
        }
        if (callback != null) callback.onComplete();
    }

    private String fetchUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "MojoLauncher/1.0");
        if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        }
    }

    private String parseLatestBuild(String json) {
        int idx = json.lastIndexOf("\"build\":");
        if (idx < 0) throw new RuntimeException("Cannot parse build number");
        int s = idx + 8; while (s < json.length() && !Character.isDigit(json.charAt(s))) s++;
        int e = s; while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        return json.substring(s, e);
    }

    private String parseSha256(String json) {
        String key = "\"sha256\":\"";
        int idx = json.lastIndexOf(key); if (idx < 0) return null;
        int s = idx + key.length(), e = json.indexOf("\"", s);
        return e < 0 ? null : json.substring(s, e);
    }

    private String findVersionUrl(String manifest, String version) {
        String marker = "\"id\":\"" + version + "\"";
        int idx = manifest.indexOf(marker); if (idx < 0) return null;
        String urlKey = "\"url\":\"";
        int ui = manifest.indexOf(urlKey, idx); if (ui < 0) return null;
        int s = ui + urlKey.length(), e = manifest.indexOf("\"", s);
        return manifest.substring(s, e);
    }

    private String parseVanillaServerUrl(String json) {
        String urlKey = "\"url\":\"";
        int serverIdx = json.indexOf("\"server\":{");
        if (serverIdx < 0) return null;
        int ui = json.indexOf(urlKey, serverIdx); if (ui < 0) return null;
        int s = ui + urlKey.length(), e = json.indexOf("\"", s);
        return json.substring(s, e);
    }

    private String parseVanillaSha1(String json) {
        String key = "\"server\":{\"sha1\":\"";
        int idx = json.indexOf(key); if (idx < 0) return null;
        int s = idx + key.length(), e = json.indexOf("\"", s);
        return e < 0 ? null : json.substring(s, e);
    }

    private List<String> parseStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String marker = "\"" + key + "\":[";
        int idx = json.indexOf(marker); if (idx < 0) return result;
        int s = idx + marker.length(), e = json.indexOf("]", s);
        if (e < 0) return result;
        for (String part : json.substring(s, e).split(",")) {
            String val = part.trim().replace("\"", "");
            if (!val.isEmpty()) result.add(val);
        }
        Collections.reverse(result);
        return result;
    }

    private void verifySha256(File file, String expected) throws IOException {
        if (!digest(file, "SHA-256").equalsIgnoreCase(expected))
            throw new IOException("SHA-256 checksum mismatch for " + file.getName());
    }

    private void verifySha1(File file, String expected) throws IOException {
        if (!digest(file, "SHA-1").equalsIgnoreCase(expected))
            throw new IOException("SHA-1 checksum mismatch for " + file.getName());
    }

    private String digest(File file, String algo) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
