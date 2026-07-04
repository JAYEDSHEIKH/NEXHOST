package net.kdt.pojavlaunch.instances;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Represents a headless Minecraft server instance managed by MojoLauncher.
 * Stores per-instance metadata, directory layout, and EULA state.
 */
public class ServerInstance {

    public enum ServerType { PAPER, VANILLA }
    public enum Status { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

    private String id;
    private String name;
    private String path;
    private ServerType serverType;
    private String mcVersion;
    private String jarFileName;
    private int port;
    private int rconPort;
    private String xms;
    private String xmx;
    private boolean acceptedEula;
    private long createdAt;
    private transient Status status = Status.STOPPED;

    private static final String META_FILE = "server_instance.properties";

    public ServerInstance() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.xms = "512M";
        this.xmx = "1024M";
        this.port = 25565;
        this.rconPort = 25575;
        this.jarFileName = "server.jar";
    }

    public ServerInstance(String name, String basePath, ServerType type, String mcVersion) {
        this();
        this.name = name;
        this.serverType = type;
        this.mcVersion = mcVersion;
        this.path = basePath + File.separator + id;
    }

    /**
     * Creates the server directory layout:
     *   <id>/server.jar, eula.txt, server.properties, world/, logs/, plugins/, backups/
     */
    public void initDirectories() throws IOException {
        new File(path).mkdirs();
        new File(path, "world").mkdirs();
        new File(path, "logs").mkdirs();
        new File(path, "plugins").mkdirs();
        new File(path, "backups").mkdirs();
        writeDefaultServerProperties();
    }

    public void writeEula() throws IOException {
        if (!acceptedEula) throw new IllegalStateException("User has not accepted the Minecraft EULA");
        String content = "# By changing the setting below to TRUE you are indicating your agreement\n"
                + "# to the Minecraft End User License Agreement (EULA).\n"
                + "# https://aka.ms/MinecraftEULA\n"
                + "eula=true\n";
        try (FileOutputStream fos = new FileOutputStream(new File(path, "eula.txt"))) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private void writeDefaultServerProperties() throws IOException {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String content = "#Minecraft server properties\n"
                + "#" + date + "\n"
                + "server-port=" + port + "\n"
                + "motd=A MojoLauncher Server\n"
                + "online-mode=true\n"
                + "max-players=20\n"
                + "difficulty=normal\n"
                + "gamemode=survival\n"
                + "level-name=world\n"
                + "enable-rcon=false\n"
                + "rcon.port=" + rconPort + "\n"
                + "rcon.password=\n"
                + "view-distance=10\n"
                + "spawn-protection=16\n";
        try (FileOutputStream fos = new FileOutputStream(new File(path, "server.properties"))) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    public void saveMeta() throws IOException {
        Properties p = new Properties();
        p.setProperty("id", id);
        p.setProperty("name", name);
        p.setProperty("serverType", serverType.name());
        p.setProperty("mcVersion", mcVersion);
        p.setProperty("jarFileName", jarFileName);
        p.setProperty("port", String.valueOf(port));
        p.setProperty("rconPort", String.valueOf(rconPort));
        p.setProperty("xms", xms);
        p.setProperty("xmx", xmx);
        p.setProperty("acceptedEula", String.valueOf(acceptedEula));
        p.setProperty("createdAt", String.valueOf(createdAt));
        try (FileOutputStream out = new FileOutputStream(path + File.separator + META_FILE)) {
            p.store(out, "MojoLauncher Server Instance");
        }
    }

    public static ServerInstance loadMeta(String instancePath) throws IOException {
        ServerInstance inst = new ServerInstance();
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(instancePath + File.separator + META_FILE)) {
            p.load(in);
        }
        inst.id = p.getProperty("id");
        inst.name = p.getProperty("name");
        inst.path = instancePath;
        inst.serverType = ServerType.valueOf(p.getProperty("serverType"));
        inst.mcVersion = p.getProperty("mcVersion");
        inst.jarFileName = p.getProperty("jarFileName", "server.jar");
        inst.port = Integer.parseInt(p.getProperty("port", "25565"));
        inst.rconPort = Integer.parseInt(p.getProperty("rconPort", "25575"));
        inst.xms = p.getProperty("xms", "512M");
        inst.xmx = p.getProperty("xmx", "1024M");
        inst.acceptedEula = Boolean.parseBoolean(p.getProperty("acceptedEula", "false"));
        inst.createdAt = Long.parseLong(p.getProperty("createdAt", "0"));
        return inst;
    }

    public static List<ServerInstance> loadAll(String basePath) {
        List<ServerInstance> list = new ArrayList<>();
        File base = new File(basePath);
        if (!base.isDirectory()) return list;
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null) return list;
        for (File dir : dirs) {
            if (new File(dir, META_FILE).exists()) {
                try { list.add(loadMeta(dir.getAbsolutePath())); }
                catch (Exception e) { /* skip corrupt instances */ }
            }
        }
        return list;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }
    public ServerType getServerType() { return serverType; }
    public String getMcVersion() { return mcVersion; }
    public String getJarFileName() { return jarFileName; }
    public void setJarFileName(String j) { this.jarFileName = j; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getXms() { return xms; }
    public void setXms(String xms) { this.xms = xms; }
    public String getXmx() { return xmx; }
    public void setXmx(String xmx) { this.xmx = xmx; }
    public boolean isAcceptedEula() { return acceptedEula; }
    public void setAcceptedEula(boolean v) { this.acceptedEula = v; }
    public long getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public String getJarPath() { return path + File.separator + jarFileName; }
}
