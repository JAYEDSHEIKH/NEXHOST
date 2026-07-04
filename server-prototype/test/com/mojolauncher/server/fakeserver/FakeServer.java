package com.mojolauncher.server.fakeserver;

import java.io.File;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * Minimal fake Minecraft server used in lifecycle integration tests.
 *
 * Behaviour:
 *   1. Sleeps 100 ms, then prints realistic startup lines.
 *   2. If a file named ".fake-crash" exists in the working directory (the
 *      instance folder), it exits with code 1 before printing "Done" — this
 *      simulates a server crash for testing crash-detection logic.
 *   3. Otherwise it prints "Done (0.1s)! For help, type \"help\"" and enters
 *      a command loop that handles: list, save-all, stop.
 *
 * Invoked by ProcessBuilder as:  java -jar fake-server.jar nogui
 * The working directory is always the server instance directory.
 */
public class FakeServer {

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;

        out.println("[Server] Starting Minecraft server");
        out.println("[Server] Loading server properties");
        out.println("[Server] Starting Minecraft server on *:25565");
        out.flush();

        Thread.sleep(100);

        // Crash-test mode: exit with code 1 before declaring ready.
        // The test creates ".fake-crash" in the instance directory (= working dir).
        if (new File(".fake-crash").exists()) {
            out.println("[Server] ERROR — simulated crash for testing!");
            out.flush();
            System.exit(1);
        }

        out.println("[Server thread/INFO]: Done (0.1s)! For help, type \"help\"");
        out.flush();

        // Command loop
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            switch (line) {
                case "list":
                    out.println("[Server thread/INFO]: There are 0 of a max of 20 players online:");
                    break;
                case "save-all":
                    out.println("[Server thread/INFO]: Saving the game");
                    out.println("[Server thread/INFO]: Saved the game");
                    break;
                case "stop":
                    out.println("[Server thread/INFO]: Stopping the server");
                    out.flush();
                    System.exit(0);
                    break;
                default:
                    out.println("[Server thread/INFO]: Unknown command. Type \"help\" for help.");
                    break;
            }
            out.flush();
        }
        // stdin closed cleanly
        System.exit(0);
    }
}
