package com.metrovoc.reprise.api;

import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.Event;

/**
 * Fired on the NeoForge game bus on the client thread after Reprise snapshots chunks and entities.
 * Mods can include their current visual state for viewers who start here or seek into this segment.
 */
public final class ReplaySnapshotEvent extends Event {
    private final ClientLevel level;
    private final Consumer<CustomPacketPayload> writer;

    public ReplaySnapshotEvent(ClientLevel level, Consumer<CustomPacketPayload> writer) {
        this.level = level;
        this.writer = writer;
    }

    public ClientLevel level() {
        return this.level;
    }

    /** Append a registered clientbound play payload during event dispatch. */
    public void write(CustomPacketPayload payload) {
        this.writer.accept(payload);
    }
}
