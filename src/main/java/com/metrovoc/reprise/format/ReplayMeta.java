package com.metrovoc.reprise.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code meta.json} of a replay. */
public final class ReplayMeta {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final int FORMAT = 1;

    public int format = FORMAT;
    public String uuid = UUID.randomUUID().toString();
    public String name = "";
    public String mcVersion = "";
    public int protocol;
    public int dataVersion;
    public boolean neoforgeConnection;
    /** Each snapshot carries its own channel negotiation. Older archives use neoforgeConnection throughout. */
    public boolean snapshotConnectionType;
    public long recordedAt;
    public int durationTicks;
    public String server = "";
    public boolean singleplayer;
    public String recorderName = "";
    public String recorderUuid = "";
    public int chunkCount;
    public List<Segment> segments = new ArrayList<>();
    public List<Marker> markers = new ArrayList<>();
    public Map<String, String> mods = new LinkedHashMap<>();

    public static final class Segment {
        public String file;
        /** Byte position immediately after SNAPSHOT_END, or zero in older recordings. */
        public int actionsOffset;
        public int startTick;
        public int ticks;
        /** The client state does not carry over from the previous segment (server switch): always run the snapshot. */
        public boolean reset;

        public Segment() {}

        public Segment(String file, int startTick, boolean reset) {
            this.file = file;
            this.startTick = startTick;
            this.reset = reset;
        }
    }

    public static final class Marker {
        public int tick;
        public String name = "";
        public int color = 0xFFD54F;
        public Double x, y, z;
        public Float yaw, pitch;
        public String dimension;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ReplayMeta fromJson(String json) {
        return GSON.fromJson(json, ReplayMeta.class);
    }
}
