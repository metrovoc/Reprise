package com.metrovoc.reprise;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class RepriseKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(Reprise.MOD_ID, "main"));

    public static final KeyMapping TOGGLE_RECORDING = key("toggle_recording", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping PAUSE_RECORDING = key("pause_recording", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping ADD_MARKER = key("add_marker", GLFW.GLFW_KEY_M);

    // Replay viewer
    public static final KeyMapping PLAY_PAUSE = key("play_pause", GLFW.GLFW_KEY_P);
    public static final KeyMapping SEEK_BACK = key("seek_back", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping SEEK_FORWARD = key("seek_forward", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping SLOWER = key("slower", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping FASTER = key("faster", GLFW.GLFW_KEY_UP);
    public static final KeyMapping OPEN_EDITOR = key("open_editor", GLFW.GLFW_KEY_TAB);
    public static final KeyMapping ADD_KEYFRAME = key("add_keyframe", GLFW.GLFW_KEY_K);
    public static final KeyMapping ROLL_LEFT = key("roll_left", GLFW.GLFW_KEY_Z);
    public static final KeyMapping ROLL_RIGHT = key("roll_right", GLFW.GLFW_KEY_C);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.reprise." + name, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping key : new KeyMapping[] {TOGGLE_RECORDING, PAUSE_RECORDING, ADD_MARKER, PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD,
            SLOWER, FASTER, OPEN_EDITOR, ADD_KEYFRAME, ROLL_LEFT, ROLL_RIGHT}) {
            event.register(key);
        }
    }

    private RepriseKeys() {}
}
