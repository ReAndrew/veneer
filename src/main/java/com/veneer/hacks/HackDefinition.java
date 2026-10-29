package com.veneer.hacks;

import java.util.Locale;

public class HackDefinition {

    private final String id;
    private final String displayName;
    private final String key;
    private final DetectionMode mode;
    private final String fallback;
    private final String lowerKey;
    private final String lowerFallback;
    private final int level;

    /** {@code level}: how serious the mod is, 1 = cheat, 2 = forbidden mod, 3 = tolerated (see config.yml). */
    public HackDefinition(String id, String displayName, String key, DetectionMode mode, int level) {
        this.id            = id;
        this.level         = level;
        this.displayName   = displayName;
        this.key           = key;
        this.mode          = mode;
        this.fallback      = "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace("-", "_") + "\u27e7";
        this.lowerKey      = key.toLowerCase(Locale.ROOT);
        this.lowerFallback = this.fallback.toLowerCase(Locale.ROOT);
    }

    public String getId()           { return id; }
    public String getDisplayName()  { return displayName; }
    public String getKey()          { return key; }
    public DetectionMode getMode()  { return mode; }
    public String getFallback()     { return fallback; }
    public String getLowerKey()     { return lowerKey; }
    public String getLowerFallback(){ return lowerFallback; }
    public int getLevel()           { return level; }
}