package com.metrovoc.reprise.playback;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Memoizes the column scan for an immutable archive terrain packet. Live lighting still belongs to the chunk. */
final class ReplaySkyLightCache {
    private static final int MAX_PACKETS = 4096;
    private final Int2ObjectLinkedOpenHashMap<long[]> columns = new Int2ObjectLinkedOpenHashMap<>();
    private ResourceKey<Level> dimension;
    private int index;
    private byte[] sections;

    void begin(int index, byte[] sections) {
        this.index = index;
        this.sections = sections;
    }

    void end() { sections = null; }

    void clear() {
        columns.clear();
        end();
    }

    void initialize(LevelChunk chunk, FriendlyByteBuf input, Runnable calculate) {
        if (sections == null || !input.hasArray() || input.array() != sections) {
            calculate.run();
            return;
        }
        if (dimension != chunk.getLevel().dimension()) {
            columns.clear();
            dimension = chunk.getLevel().dimension();
        }
        long[] target = chunk.getSkyLightSources().heightmap.getRaw();
        long[] cached = columns.getAndMoveToLast(index);
        if (cached == null || cached.length != target.length) {
            calculate.run();
            // A later block update changes the chunk's live column heights.
            columns.put(index, target.clone());
            if (columns.size() > MAX_PACKETS) columns.removeFirst();
        } else {
            System.arraycopy(cached, 0, target, 0, target.length);
        }
    }
}
