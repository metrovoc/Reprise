package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.editor.CameraPose;
import com.metrovoc.reprise.editor.EditorScreen;
import com.metrovoc.reprise.editor.Keyframe;
import com.metrovoc.reprise.editor.PathInterpolator;
import com.metrovoc.reprise.export.VideoExporter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Draws the camera path in the world while the editor is open: the curve, each keyframe, and where it looks. */
public final class PathPreview {
    private static final int PATH = 0xFFFFC94D;
    private static final int KEY = 0xFFFF7A3D;
    private static final int LOOK = 0xFF7FD4FF;

    public static void emit(ReplaySession session) {
        Minecraft mc = Minecraft.getInstance();
        List<Keyframe> keys = session.project().keyframes;
        if (keys.isEmpty() || VideoExporter.isRunning() || !(mc.screen instanceof EditorScreen) || mc.player == null) {
            return;
        }
        double eye = mc.player.getEyeHeight();
        double first = keys.get(0).tick, last = keys.get(keys.size() - 1).tick;
        // About two samples per tick, but never more than 2000 segments.
        double step = Math.max(0.5, (last - first) / 2000.0);
        Vec3 prev = null;
        for (double t = first; t <= last + 1e-6; t += step) {
            CameraPose p = PathInterpolator.evaluate(keys, Math.min(t, last));
            Vec3 pos = new Vec3(p.x(), p.y() + eye, p.z());
            if (prev != null) {
                Gizmos.line(prev, pos, PATH, 2.0F);
            }
            prev = pos;
        }
        for (Keyframe k : keys) {
            Vec3 pos = new Vec3(k.x, k.y + eye, k.z);
            Gizmos.point(pos, KEY, 8.0F);
            Gizmos.arrow(pos, pos.add(look(k.yaw, k.pitch)), LOOK, 2.0F);
        }
    }

    private static Vec3 look(float yaw, float pitch) {
        float y = yaw * Mth.DEG_TO_RAD, x = pitch * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(y) * Mth.cos(x), -Mth.sin(x), Mth.cos(y) * Mth.cos(x));
    }

    private PathPreview() {}
}
