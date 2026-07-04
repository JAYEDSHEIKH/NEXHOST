package com.mojolauncher.server;

import com.mojolauncher.server.api.ServerApi;
import com.mojolauncher.server.backup.BackupManager;
import com.mojolauncher.server.downloader.ServerJarDownloader;
import com.mojolauncher.server.lifecycle.ConsoleListener;
import com.mojolauncher.server.lifecycle.ServerProcessManager;
import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;
import com.mojolauncher.server.ui.ConsoleUI;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Entry point for the MojoLauncher server-hosting prototype.
 *
 * Modes:
 *   Interactive CLI (default):
 *     java -cp out com.mojolauncher.server.Main [instances-dir]
 *
 *   HTTP API server:
 *     java -cp out com.mojolauncher.server.Main [instances-dir] --http [--port 8080]
 *
 * Interactive CLI commands:
 *   create   - Create a new server instance
 *   list     - List all instances
 *   start    - Start a server instance
 *   stop     - Stop a running instance
 *   console  - Attach console to a running instance
 *   backup   - Back up world data
 *   versions - List available server versions
 *   delete   - Delete an instance
 *   quit     - Exit
 */
public class Main {

    private static final String DEFAULT_BASE = System.getProperty("user.home") + File.separator + "mojo-servers";
    private final String basePath;
    private final Scanner scanner = new Scanner(System.in);
    private final Map<String, ServerProcessManager> managers = new LinkedHashMap<>();

    public Main(String basePath) {
        this.basePath = basePath;
        new File(basePath).mkdirs();
    }

    public static void main(String[] args) throws Exception {
        // Parse arguments
        String base    = DEFAULT_BASE;
        boolean httpMode = false;
        int     port   = 8080;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--http".equals(a)) {
                httpMode = true;
            } else if ("--port".equals(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (a.startsWith("--port=")) {
                port = Integer.parseInt(a.substring(7));
            } else if (!a.startsWith("--")) {
                base = a;
            }
        }

        System.out.println("MojoLauncher Server Prototype");
        System.out.println("Instance directory: " + base);
        System.out.println();

        if (httpMode) {
            runHttpMode(base, port);
        } else {
            new Main(base).run();
        }
    }

    // ── HTTP API mode ─────────────────────────────────────────────────────────

    private static void runHttpMode(String base, int port) throws Exception {
        ServerApi api = new ServerApi(port, base);
        api.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[API] Shutting down...");
            api.stop();
        }));

        System.out.println("[API] Press Ctrl+C to stop.");
        System.out.println("[API] Try: curl http://localhost:" + port + "/api/v1/instances");
        Thread.currentThread().join(); // block until JVM shutdown
    }

    // ── Interactive CLI mode ──────────────────────────────────────────────────

    private void run() throws Exception {
        printHelp();
        while (true) {
            System.out.print("\n[mojo-server] > ");
            String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "quit";
            String[] parts = input.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            switch (cmd) {
                case "create":   cmdCreate(); break;
                case "list":     cmdList(); break;
                case "start":    cmdStart(); break;
                case "stop":     cmdStop(); break;
                case "console":  cmdConsole(); break;
                case "backup":   cmdBackup(); break;
                case "versions": cmdVersions(); break;
                case "delete":   cmdDelete(); break;
                case "help":     printHelp(); break;
                case "quit": case "exit":
                    System.out.println("Stopping all running servers...");
                    stopAll();
                    System.out.println("Goodbye.");
                    return;
                default:
                    System.out.println("Unknown command: " + cmd + " (type 'help' for options)");
            }
        }
    }

    private void cmdCreate() throws IOException {
        System.out.print("Server name: ");
        String name = scanner.nextLine().trim();
        if (name.isEmpty()) { System.out.println("Name cannot be empty."); return; }

        System.out.print("Server type [paper/vanilla] (default: paper): ");
        String typeStr = scanner.nextLine().trim().toLowerCase();
        ServerType type = typeStr.equals("vanilla") ? ServerType.VANILLA : ServerType.PAPER;

        System.out.print("Minecraft version (e.g. 1.21.4): ");
        String version = scanner.nextLine().trim();
        if (version.isEmpty()) { System.out.println("Version cannot be empty."); return; }

        System.out.print("Memory Xms (default: 512M): ");
        String xms = scanner.nextLine().trim();
        if (xms.isEmpty()) xms = "512M";

        System.out.print("Memory Xmx (default: 1024M): ");
        String xmx = scanner.nextLine().trim();
        if (xmx.isEmpty()) xmx = "1024M";

        System.out.print("Server port (default: 25565): ");
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? 25565 : Integer.parseInt(portStr);

        System.out.println();
        System.out.println("By changing the setting below to TRUE you are indicating your agreement");
        System.out.println("to the Minecraft End User License Agreement (EULA).");
        System.out.println("https://aka.ms/MinecraftEULA");
        System.out.print("Do you accept the Minecraft EULA? [yes/no]: ");
        String eulaAnswer = scanner.nextLine().trim().toLowerCase();
        if (!eulaAnswer.equals("yes") && !eulaAnswer.equals("y")) {
            System.out.println("You must accept the EULA to create a server instance.");
            return;
        }

        ServerInstance inst = new ServerInstance(name, basePath, type, version);
        inst.setXms(xms);
        inst.setXmx(xmx);
        inst.setPort(port);
        inst.setAcceptedEula(true);

        System.out.println("\nCreating instance directories...");
        inst.initDirectories();
        inst.writeEula();

        System.out.println("Downloading server jar (" + type.displayName + " " + version + ")...");
        new ServerJarDownloader().download(inst);

        inst.saveMeta();
        System.out.println("\nInstance '" + name + "' created successfully at: " + inst.getPath());
        System.out.println("Run 'start' and choose this instance to launch it.");
    }

    private void cmdList() {
        List<ServerInstance> instances = ServerInstance.loadAll(basePath);
        if (instances.isEmpty()) {
            System.out.println("No instances found. Use 'create' to make one.");
            return;
        }
        System.out.println();
        System.out.printf("  %-30s %-10s %-12s %-8s %s%n", "Name", "Type", "Version", "Port", "Status");
        System.out.println("  " + "-".repeat(75));
        for (ServerInstance i : instances) {
            ServerProcessManager mgr = managers.get(i.getId());
            String status = mgr != null && mgr.isRunning() ? "RUNNING" : i.getStatus().name();
            System.out.printf("  %-30s %-10s %-12s %-8d %s  [%s]%n",
                    i.getName(), i.getServerType().displayName, i.getMcVersion(),
                    i.getPort(), status, i.getId());
        }
    }

    private void cmdStart() throws IOException {
        ServerInstance inst = pickInstance("start");
        if (inst == null) return;
        ServerProcessManager existing = managers.get(inst.getId());
        if (existing != null && existing.isRunning()) {
            System.out.println("Server '" + inst.getName() + "' is already running.");
            return;
        }
        ServerProcessManager mgr = new ServerProcessManager(inst);
        mgr.addListener(new ConsoleListener() {
            @Override public void onLine(String line) { System.out.println("[" + inst.getName() + "] " + line); }
            @Override public void onServerStarted() { System.out.println("*** " + inst.getName() + " is ready! ***"); }
            @Override public void onServerStopped(int code) { System.out.println("*** " + inst.getName() + " stopped (exit " + code + ") ***"); }
            @Override public void onCrash(int code, String tail) {
                System.err.println("*** " + inst.getName() + " CRASHED (exit " + code + ") ***");
                System.err.println(tail);
            }
        });
        managers.put(inst.getId(), mgr);
        mgr.start();
        System.out.println("Server '" + inst.getName() + "' is starting. Use 'console' to attach.");
    }

    private void cmdStop() throws IOException, InterruptedException {
        ServerInstance inst = pickInstance("stop");
        if (inst == null) return;
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr == null || !mgr.isRunning()) {
            System.out.println("Server '" + inst.getName() + "' is not running.");
            return;
        }
        System.out.println("Creating backup before stop...");
        try {
            File zip = new BackupManager(inst).createBackup();
            System.out.println("Backup saved to: " + zip.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Backup failed (continuing with stop): " + e.getMessage());
        }
        System.out.println("Stopping server '" + inst.getName() + "'...");
        mgr.stopGraceful();
        System.out.println("Server stopped.");
    }

    private void cmdConsole() {
        ServerInstance inst = pickInstance("attach console to");
        if (inst == null) return;
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr == null || !mgr.isRunning()) {
            System.out.println("Server '" + inst.getName() + "' is not running. Start it first.");
            return;
        }
        new ConsoleUI(inst, mgr).run();
    }

    private void cmdBackup() throws IOException {
        ServerInstance inst = pickInstance("back up");
        if (inst == null) return;
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr != null && mgr.isRunning()) {
            System.out.println("Running save-all before backup...");
            try { mgr.sendCommand("save-all"); Thread.sleep(2000); }
            catch (Exception ignored) {}
        }
        File zip = new BackupManager(inst).createBackup();
        System.out.println("Backup created: " + zip.getAbsolutePath());
    }

    private void cmdVersions() throws IOException {
        System.out.print("Server type [paper/vanilla] (default: paper): ");
        String typeStr = scanner.nextLine().trim().toLowerCase();
        ServerType type = typeStr.equals("vanilla") ? ServerType.VANILLA : ServerType.PAPER;
        System.out.println("Fetching available versions...");
        List<String> versions = new ServerJarDownloader().fetchAvailableVersions(type);
        System.out.println("Available versions for " + type.displayName + ":");
        int shown = Math.min(versions.size(), 20);
        for (int i = 0; i < shown; i++) {
            System.out.println("  " + versions.get(i));
        }
        if (versions.size() > shown) {
            System.out.println("  ... and " + (versions.size() - shown) + " more");
        }
    }

    private void cmdDelete() throws IOException {
        ServerInstance inst = pickInstance("delete");
        if (inst == null) return;
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr != null && mgr.isRunning()) {
            System.out.println("Server is running. Stop it first.");
            return;
        }
        System.out.print("Are you sure you want to delete '" + inst.getName() + "'? All data will be lost. [yes/no]: ");
        String confirm = scanner.nextLine().trim().toLowerCase();
        if (!confirm.equals("yes") && !confirm.equals("y")) {
            System.out.println("Cancelled.");
            return;
        }
        if (mgr != null) mgr.shutdown();
        deleteRecursively(new File(inst.getPath()));
        managers.remove(inst.getId());
        System.out.println("Deleted instance: " + inst.getName());
    }

    private ServerInstance pickInstance(String action) {
        List<ServerInstance> instances = ServerInstance.loadAll(basePath);
        if (instances.isEmpty()) {
            System.out.println("No instances found. Use 'create' to make one.");
            return null;
        }
        if (instances.size() == 1) return instances.get(0);
        System.out.println("Select an instance to " + action + ":");
        for (int i = 0; i < instances.size(); i++) {
            System.out.printf("  %d) %s (%s %s)%n",
                    i + 1, instances.get(i).getName(),
                    instances.get(i).getServerType().displayName,
                    instances.get(i).getMcVersion());
        }
        System.out.print("Choice [1-" + instances.size() + "]: ");
        try {
            int choice = Integer.parseInt(scanner.nextLine().trim()) - 1;
            if (choice < 0 || choice >= instances.size()) {
                System.out.println("Invalid choice.");
                return null;
            }
            return instances.get(choice);
        } catch (NumberFormatException e) {
            System.out.println("Invalid input.");
            return null;
        }
    }

    private void stopAll() {
        for (ServerProcessManager mgr : managers.values()) {
            if (mgr.isRunning()) {
                try { mgr.stopGraceful(); } catch (Exception e) { mgr.forceStop(); }
            }
            mgr.shutdown();
        }
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) for (File c : Objects.requireNonNull(f.listFiles())) deleteRecursively(c);
        f.delete();
    }

    private void printHelp() {
        System.out.println("Commands: create | list | start | stop | console | backup | versions | delete | quit");
        System.out.println("HTTP API: restart with --http [--port 8080]");
    }
}
