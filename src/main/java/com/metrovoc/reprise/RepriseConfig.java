package com.metrovoc.reprise;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RepriseConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_SINGLEPLAYER;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_MULTIPLAYER;
    public static final ModConfigSpec.BooleanValue SHOW_RECORDING_INDICATOR;
    public static final ModConfigSpec.BooleanValue RECORD_HOTBAR;

    public static final ModConfigSpec.IntValue POV_DIVISIONS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.translation("reprise.configuration.recording").push("recording");
        AUTO_RECORD_SINGLEPLAYER = b.comment("Start recording when a singleplayer world opens")
            .translation("reprise.configuration.autoRecordSingleplayer").define("autoRecordSingleplayer", false);
        AUTO_RECORD_MULTIPLAYER = b.comment("Start recording when joining a server")
            .translation("reprise.configuration.autoRecordMultiplayer").define("autoRecordMultiplayer", false);
        SHOW_RECORDING_INDICATOR = b.comment("Show a red dot and the recorded time while recording")
            .translation("reprise.configuration.showIndicator").define("showIndicator", true);
        RECORD_HOTBAR = b.comment("Record your nine hotbar slots, selection, health, food and experience for first-person replay")
            .translation("reprise.configuration.recordHotbar").define("recordHotbar", false);
        POV_DIVISIONS = b.comment("First-person sample intervals per game tick (1 uses ordinary tick recording)")
            .translation("reprise.configuration.povDivisions").defineInRange("povDivisions", 1, 1, 12);
        b.pop();
        SPEC = b.build();
    }

    private RepriseConfig() {}
}
