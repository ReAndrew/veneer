package com.veneer.hacks.listeners;

import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.UUID;

public class SignListener implements Listener {

    private final VeneerPlugin plugin;

    public SignListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        boolean hackChecking = plugin.getCheckManager().isChecking(uuid);
        boolean langChecking = plugin.getLangCheckManager().isChecking(uuid);
        if (!hackChecking && !langChecking) return;

        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            net.kyori.adventure.text.Component c = event.line(i);
            lines[i] = c != null ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        }

        if (hackChecking) plugin.getCheckManager().handleBatchResponse(player, lines);
        else              plugin.getLangCheckManager().handleResponse(player, lines);
    }
}