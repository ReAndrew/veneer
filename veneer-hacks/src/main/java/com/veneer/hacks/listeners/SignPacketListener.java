package com.veneer.hacks.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HacksModule;
import org.bukkit.entity.Player;

/**
 * Intercepts sign update packets sent in response to virtual sign probes.
 * Handles both mod checks and language/version checks at packet level,
 * before Paper can truncate lines to 80 characters or reject the packet because the world block is air.
 */
public class SignPacketListener extends PacketListenerAbstract {

    private final VeneerPlugin plugin;

    public SignPacketListener(VeneerPlugin plugin) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player player = event.getPlayer();
        if (player == null && event.getUser().getUUID() != null) {
            player = plugin.getServer().getPlayer(event.getUser().getUUID());
        }
        if (player == null) return;

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        Vector3i pos = packet.getBlockPosition();
        String[] lines = packet.getTextLines();
        if (lines == null) return;

        HacksModule hacks = plugin.module(HacksModule.class);
        if (hacks == null) return;

        if (hacks.checks().acceptSignUpdate(player, pos.getX(), pos.getY(), pos.getZ(), lines)
                || hacks.langChecks().acceptSignUpdate(player, pos.getX(), pos.getY(), pos.getZ(), lines)) {
            event.setCancelled(true);
        }
    }
}
