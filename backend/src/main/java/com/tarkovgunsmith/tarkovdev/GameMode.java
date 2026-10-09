package com.tarkovgunsmith.tarkovdev;

/** Game mode as used in json.tarkov.dev paths. */
public enum GameMode {
    REGULAR("regular"),
    PVE("pve");

    private final String path;

    GameMode(String path) {
        this.path = path;
    }

    /** Path segment, e.g. {@code regular} in {@code /regular/items}. */
    public String path() {
        return path;
    }
}
