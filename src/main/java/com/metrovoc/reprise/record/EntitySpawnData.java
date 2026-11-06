package com.metrovoc.reprise.record;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;

/**
 * The type-specific {@code data} field of each entity's spawn packet (a falling block's state, a projectile's owner, a
 * frame's facing, or whatever a mod puts there), kept so that a snapshot can spawn the entity the way the server did.
 */
final class EntitySpawnData {
    private final Int2IntOpenHashMap data = new Int2IntOpenHashMap();

    void observe(Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            this.data.put(add.getId(), add.getData());
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach(this.data::remove);
        }
    }

    int get(int entityId, int fallback) {
        return this.data.getOrDefault(entityId, fallback);
    }
}
