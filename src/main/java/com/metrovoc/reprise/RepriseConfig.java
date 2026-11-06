package com.metrovoc.reprise;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RepriseConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_SINGLEPLAYER;
    public static final ModConfigSpec.BooleanValue AUTO_RECORD_MULTIPLAYER;
    public static final ModConfigSpec.BooleanValue SHOW_RECORDING_INDICATOR;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.translation("reprise.configuration.recording").push("recording");
        AUTO_RECORD_SINGLEPLAYER = b.comment("Start recording when a singleplayer world opens")
            .translation("reprise.configuration.autoRecordSingleplayer").define("autoRecordSingleplayer", false);
        AUTO_RECORD_MULTIPLAYER = b.comment("Start recording when joining a server")
            .translation("reprise.configuration.autoRecordMultiplayer").define("autoRecordMultiplayer", false);
        SHOW_RECORDING_INDICATOR = b.comment("Show a red dot and the recorded time while recording")
            .translation("reprise.configuration.showIndicator").define("showIndicator", true);
        b.pop();
        SPEC = b.build();
    }

    private RepriseConfig() {}
}
