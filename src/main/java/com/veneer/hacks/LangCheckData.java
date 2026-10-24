package com.veneer.hacks;

import com.veneer.core.WrappedTask;
import org.bukkit.Location;
import org.bukkit.block.BlockState;

import java.util.Map;
import java.util.UUID;

public class LangCheckData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final Map<String, String> languages;

    private Location signLocation;
    private BlockState originalState;
    private WrappedTask timeoutTask;
    private int deadWaits;

    /**
     * Counts one more wait (a second while the player cannot open a sign, or one sign asked again because the
     * player died, slept, changed world or moved away); the check gives up after 60 of them.
     */
    public int waitWhileBusy() { return ++deadWaits; }

    public LangCheckData(UUID targetUUID, UUID initiatorUUID, Map<String, String> languages) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.languages     = languages;
    }

    public UUID getTargetUUID()                { return targetUUID; }
    public UUID getInitiatorUUID()             { return initiatorUUID; }
    public Map<String, String> getLanguages()  { return languages; }
    public Location getSignLocation()          { return signLocation; }
    public void setSignLocation(Location l)    { this.signLocation = l; }
    public BlockState getOriginalState()       { return originalState; }
    public void setOriginalState(BlockState s) { this.originalState = s; }
    public WrappedTask getTimeoutTask()         { return timeoutTask; }
    public void setTimeoutTask(WrappedTask t)   { this.timeoutTask = t; }
}
