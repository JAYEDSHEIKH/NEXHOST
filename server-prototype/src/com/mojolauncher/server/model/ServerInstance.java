package com.mojolauncher.server.model;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ServerInstance {

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
    private Status status;

    private static final String META_FILE = "instance.properties";

    public ServerInstance() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.status = Status.STOPPED;
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

    public void initDirectories() throws IOException {
        Files.createDirectories(Paths.get(path));
        Files.createDirectories(Paths.get(path, "world"));
        Files.createDirectories(Paths.get(path, "logs"));
        Files.createDirectories(Paths.get(path, "plugins"));
        Files.createDirectories(Paths.get(path, "backups"));
        writeServerProperties();
    }

    public void writeEula() throws IOException {
        if (!acceptedEula) throw new IllegalStateException("User has not accepted the Minecraft EULA");
        String content = "# By changing the setting below to TRUE you are indicating your agreement\n"
                + "# to the Minecraft End User License Agreement (EULA).\n"
                + "# https://aka.ms/MinecraftEULA\n"
                + "eula=true\n";
        Files.write(Paths.get(path, "eula.txt"), content.getBytes());
    }

    private void writeServerProperties() throws IOException {
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
        Files.write(Paths.get(path, "server.properties"), content.getBytes());
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
            p.store(out, "MojoLauncher Server Instance Metadata");
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
        inst.status = Status.STOPPED;
        return inst;
    }

    public static List<ServerInstance> loadAll(String basePath) {
        List<ServerInstance> list = new ArrayList<>();
        File base = new File(basePath);
        if (!base.isDirectory()) return list;
        for (File dir : Objects.requireNonNull(base.listFiles(File::isDirectory))) {
            File meta = new File(dir, META_FILE);
            if (meta.exists()) {
                try {
                    list.add(loadMeta(dir.getAbsolutePath()));
                } catch (Exception e) {
                    System.err.println("Failed to load instance at " + dir + ": " + e.getMessage());
                }
            }
        }
        return list;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }
    public ServerType getServerType() { return serverType; }
    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }
    public String getJarFileName() { return jarFileName; }
    public void setJarFileName(String jarFileName) { this.jarFileName = jarFileName; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getRconPort() { return rconPort; }
    public String getXms() { return xms; }
    public void setXms(String xms) { this.xms = xms; }
    public String getXmx() { return xmx; }
    public void setXmx(String xmx) { this.xmx = xmx; }
    public boolean isAcceptedEula() { return acceptedEula; }
    public void setAcceptedEula(boolean acceptedEula) { this.acceptedEula = acceptedEula; }
    public long getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getJarPath() { return path + File.separator + jarFileName; }
}
