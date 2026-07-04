package com.mojolauncher.server.lifecycle;

public interface ConsoleListener {
    void onLine(String line);
    void onServerStarted();
    void onServerStopped(int exitCode);
    void onCrash(int exitCode, String lastLines);
}
