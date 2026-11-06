package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.util.Varints;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Set;
import net.minecraft.network.protocol.game.GameProtocols;
import static net.minecraft.network.protocol.game.GamePacketTypes.*;

/** Terrain edits have discrete timestamps. Actor transforms supply interpolation samples for the interval before them. */
final class ExportTerrainClock {
    private static final BitSet TERRAIN = new BitSet();
    private static final BitSet MOTION = new BitSet();
    static {
        var terrainTypes = Set.of(CLIENTBOUND_BLOCK_UPDATE, CLIENTBOUND_SECTION_BLOCKS_UPDATE, CLIENTBOUND_BLOCK_ENTITY_DATA,
            CLIENTBOUND_BLOCK_EVENT, CLIENTBOUND_BLOCK_DESTRUCTION, CLIENTBOUND_CHUNKS_BIOMES, CLIENTBOUND_LIGHT_UPDATE,
            CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, CLIENTBOUND_FORGET_LEVEL_CHUNK, CLIENTBOUND_SET_CHUNK_CACHE_CENTER,
            CLIENTBOUND_SET_CHUNK_CACHE_RADIUS, CLIENTBOUND_CHUNK_BATCH_START, CLIENTBOUND_CHUNK_BATCH_FINISHED);
        var motionTypes = Set.of(CLIENTBOUND_MOVE_ENTITY_POS, CLIENTBOUND_MOVE_ENTITY_ROT, CLIENTBOUND_MOVE_ENTITY_POS_ROT,
            CLIENTBOUND_ENTITY_POSITION_SYNC, CLIENTBOUND_TELEPORT_ENTITY, CLIENTBOUND_ROTATE_HEAD, CLIENTBOUND_SET_ENTITY_MOTION);
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (terrainTypes.contains(type)) TERRAIN.set(id);
            if (motionTypes.contains(type)) MOTION.set(id);
        });
    }

    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private int visibleTick = Integer.MAX_VALUE;
    private int pendingTick;
    private boolean orderedSuffix;

    boolean ahead(int tick) { return tick > visibleTick; }

    boolean shouldHold(byte[] bytes) {
        int id;
        try {
            id = Varints.read(ByteBuffer.wrap(bytes));
        } catch (java.nio.BufferUnderflowException | IllegalStateException malformed) {
            id = -1; // Leave the playback decoder in charge of reporting invalid packets.
        }
        if (id >= 0 && TERRAIN.get(id)) return true;
        if (pending.isEmpty()) return false;
        // Motion is independent of terrain. Once another packet (e.g. spawn, respawn or
        // custom payload) follows an edit, keep its entire remaining packet sequence together.
        if (id >= 0 && MOTION.get(id) && !orderedSuffix) return false;
        orderedSuffix = true;
        return true;
    }

    void hold(int tick, Runnable apply) {
        pendingTick = tick;
        pending.addLast(apply);
    }

    void holdConfiguration(int tick, Runnable apply) {
        orderedSuffix = true;
        hold(tick, apply);
    }

    void discard() {
        visibleTick = Integer.MAX_VALUE;
        pending.clear();
        orderedSuffix = false;
    }

    boolean advance(int tick) {
        visibleTick = tick;
        if (tick >= pendingTick && !pending.isEmpty()) {
            while (!pending.isEmpty()) pending.removeFirst().run();
            orderedSuffix = false;
            return true;
        }
        return false;
    }
}
