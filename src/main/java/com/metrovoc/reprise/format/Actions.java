package com.metrovoc.reprise.format;

/**
 * Segment file layout: {@link #MAGIC}, {@link #VERSION}, then a stream of actions. Each action is one id byte followed
 * by its payload. A segment opens with a snapshot (everything up to {@link #SNAPSHOT_END}) that rebuilds the client's
 * state from nothing; playback runs it only when it enters the segment by seeking or after a reset.
 */
public final class Actions {
    public static final int MAGIC = 0x52505253; // "RPRS"
    public static final int VERSION = 1;
    /** Version 2 segments may retain tick boundaries inside a clip's opening snapshot. */
    public static final int SNAPSHOT_TICK_VERSION = 2;

    /** Version 3 allows another configuration handshake in the timed or folded packet stream. */
    public static final int RECONFIGURATION_VERSION = 3;

    public static final int POV_SAMPLES_VERSION = 4;
    /** Length-prefixed sub-tick camera samples for a recorded entity. */
    public static final int POV_SAMPLES = 6;

    /** Version 5 native POV batches store each changing channel as XORs of consecutive IEEE values. */
    public static final int PACKED_POV_VERSION = 5;
    public static final int PACKED_POV = 7;

    /** Version 6 records local first-person walking, eye height and field-of-view animation. */
    public static final int VIEW_MOTION_VERSION = 6;
    public static final int VIEW_MOTION = 8;

    /** Ends one recorded client tick. No payload. */
    public static final int TICK = 0;
    /** A clientbound play packet: varint length, then the packet as the game codec writes it (id + body). */
    public static final int GAME_PACKET = 1;
    /** A clientbound configuration packet: varint length, then id + body. */
    public static final int CONFIG_PACKET = 2;
    /** A chunk packet stored once in the chunk cache: varint cache index. */
    public static final int CHUNK_REF = 3;
    /** Marks the end of the segment's snapshot. No payload. */
    public static final int SNAPSHOT_END = 4;

    /** End of a folded tick. Processes pending world updates without advancing the clip timeline. */
    public static final int SNAPSHOT_TICK = 5;

    /** Chunk packets per chunk cache file. */
    public static final int CHUNKS_PER_FILE = 2048;

    public static String segmentFile(int index) {
        return "segments/" + index + ".bin";
    }

    public static String chunkFile(int fileIndex) {
        return "chunks/" + fileIndex + ".bin";
    }

    private Actions() {}
}
