package com.metrovoc.reprise;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class RepriseKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(Reprise.MOD_ID, "main"));

    /**
     * Keys that only do something while watching a replay, so they share letters with gameplay keys the way the
     * reference mods do (P to play, I for a camera key, J and L to roll). Vanilla actions on the same keys that would
     * also fire during a replay are swallowed there (see PlaybackControls).
     */
    public static final net.neoforged.neoforge.client.settings.IKeyConflictContext IN_REPLAY = new net.neoforged.neoforge.client.settings.IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return com.metrovoc.reprise.playback.ReplaySession.isActive();
        }

        @Override
        public boolean conflicts(net.neoforged.neoforge.client.settings.IKeyConflictContext other) {
            return other == this;
        }
    };

    public static final KeyMapping TOGGLE_RECORDING = key("toggle_recording", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping PAUSE_RECORDING = key("pause_recording", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping ADD_MARKER = key("add_marker", GLFW.GLFW_KEY_M, net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME);

    // Replay viewer
    public static final KeyMapping PLAY_PAUSE = key("play_pause", GLFW.GLFW_KEY_P, IN_REPLAY);
    public static final KeyMapping SEEK_BACK = key("seek_back", GLFW.GLFW_KEY_LEFT, IN_REPLAY);
    public static final KeyMapping SEEK_FORWARD = key("seek_forward", GLFW.GLFW_KEY_RIGHT, IN_REPLAY);
    public static final KeyMapping SLOWER = key("slower", GLFW.GLFW_KEY_DOWN, IN_REPLAY);
    public static final KeyMapping FASTER = key("faster", GLFW.GLFW_KEY_UP, IN_REPLAY);
    public static final KeyMapping OPEN_EDITOR = key("open_editor", GLFW.GLFW_KEY_TAB, IN_REPLAY);
    public static final KeyMapping ADD_KEYFRAME = key("add_keyframe", GLFW.GLFW_KEY_I, IN_REPLAY);
    public static final KeyMapping ROLL_LEFT = key("roll_left", GLFW.GLFW_KEY_J, IN_REPLAY);
    public static final KeyMapping PATH_PREVIEW = key("path_preview", GLFW.GLFW_KEY_H, IN_REPLAY);
    public static final KeyMapping PLAYERS = key("players", GLFW.GLFW_KEY_B, IN_REPLAY);
    public static final KeyMapping ROLL_RIGHT = key("roll_right", GLFW.GLFW_KEY_L, IN_REPLAY);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.reprise." + name, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    private static KeyMapping key(String name, int code, net.neoforged.neoforge.client.settings.IKeyConflictContext context) {
        return new KeyMapping("key.reprise." + name, context, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    static void register(RegisterKeyMappingsEvent event) {

        event.registerCategory(CATEGORY);
        for (KeyMapping key : new KeyMapping[] {TOGGLE_RECORDING, PAUSE_RECORDING, ADD_MARKER, PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD,
            SLOWER, FASTER, OPEN_EDITOR, ADD_KEYFRAME, ROLL_LEFT, ROLL_RIGHT, PATH_PREVIEW, PLAYERS}) {
            event.register(key);
        }
    }

    private RepriseKeys() {}
}
