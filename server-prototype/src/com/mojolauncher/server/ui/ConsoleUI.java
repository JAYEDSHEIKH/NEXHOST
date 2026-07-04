package com.mojolauncher.server.ui;

import com.mojolauncher.server.lifecycle.ConsoleListener;
import com.mojolauncher.server.lifecycle.ServerProcessManager;
import com.mojolauncher.server.model.ServerInstance;

import java.io.IOException;
import java.util.Scanner;

/**
 * Simple terminal-based console UI for interacting with a running server.
 * Reads commands from stdin and displays server output on stdout.
 */
public class ConsoleUI implements ConsoleListener {

    private final ServerProcessManager manager;
    private final ServerInstance instance;
    private volatile boolean running = true;

    public ConsoleUI(ServerInstance instance, ServerProcessManager manager) {
        this.instance = instance;
        this.manager = manager;
    }

    public void run() {
        manager.addListener(this);
        System.out.println("=== Console for: " + instance.getName() + " ===");
        System.out.println("Type commands and press Enter. Type 'exit' to detach, 'stop' to shut down the server.");

        Scanner scanner = new Scanner(System.in);
        while (running) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("exit")) {
                System.out.println("[Console] Detached from console. Server is still running.");
                break;
            }
            if (!manager.isRunning()) {
                System.out.println("[Console] Server is not running.");
                break;
            }
            try {
                manager.sendCommand(line);
            } catch (IOException e) {
                System.err.println("[Console] Failed to send command: " + e.getMessage());
            }
        }
        manager.removeListener(this);
    }

    @Override
    public void onLine(String line) {
        System.out.println(line);
    }

    @Override
    public void onServerStarted() {
        System.out.println("[Console] *** Server is ready! ***");
    }

    @Override
    public void onServerStopped(int exitCode) {
        System.out.println("[Console] Server stopped (exit code " + exitCode + ")");
        running = false;
    }

    @Override
    public void onCrash(int exitCode, String lastLines) {
        System.err.println("[Console] *** SERVER CRASHED (exit code " + exitCode + ") ***");
        System.err.println("[Console] Last output:\n" + lastLines);
        running = false;
    }
}
