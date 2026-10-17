package com.veneer.hacks.managers;

import com.veneer.hacks.ClientType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientDataManager {

    private final Map<UUID, ClientType> clientTypes = new ConcurrentHashMap<>();

    public void setClientType(UUID uuid, ClientType type) {
        clientTypes.put(uuid, type);
    }

    public ClientType getClientType(UUID uuid) {
        return clientTypes.getOrDefault(uuid, ClientType.UNKNOWN);
    }

    public void remove(UUID uuid) {
        clientTypes.remove(uuid);
    }
}