package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.editor.CameraPose;
import com.metrovoc.reprise.editor.PathInterpolator;
import com.metrovoc.reprise.editor.ReplayProject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Moves the camera along the keyframed path while the path preview plays, and during export. */
public final class CameraPath {
    private static boolean preview;
    private static boolean forced;
    private static CameraPose current;

    public static boolean preview() {
        return preview;
    }

    public static void setPreview(boolean value) {
        preview = value;
    }

    /** Export drives the path regardless of the preview toggle. */
    public static void setForced(boolean value) {
        forced = value;
    }

    public static boolean isDriving() {
        ReplaySession session = ReplaySession.active();
        Minecraft mc = Minecraft.getInstance();
        return session != null && session.project() != null && !session.project().keyframes.isEmpty()
            && mc.getCameraEntity() == mc.player && (forced || preview && session.isPlaying());
    }

    /** The pose applied this frame, for roll and field of view; null when the path is not driving. */
    public static CameraPose current() {
        return current;
    }

    public static void frame(double tick) {
        if (!isDriving()) {
            current = null;
            return;
        }
        ReplayProject project = ReplaySession.active().project();
        current = PathInterpolator.evaluate(project.keyframes, tick);
        apply(current);
    }

    public static void apply(CameraPose pose) {
        LocalPlayer camera = Minecraft.getInstance().player;
        if (camera == null || pose == null) {
            return;
        }
        camera.snapTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        camera.setOldPosAndRot();
        camera.setYHeadRot(pose.yaw());
        current = pose;
    }

    private CameraPath() {}
}
