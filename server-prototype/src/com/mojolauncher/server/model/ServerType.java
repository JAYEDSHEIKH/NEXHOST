package com.mojolauncher.server.model;

public enum ServerType {
    VANILLA("Vanilla", "https://launchermeta.mojang.com/mc/game/version_manifest.json"),
    PAPER("Paper", "https://api.papermc.io/v2/projects/paper");

    public final String displayName;
    public final String apiUrl;

    ServerType(String displayName, String apiUrl) {
        this.displayName = displayName;
        this.apiUrl = apiUrl;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
