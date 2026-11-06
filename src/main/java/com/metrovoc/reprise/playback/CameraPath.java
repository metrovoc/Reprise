package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.editor.CameraPose;
import com.metrovoc.reprise.editor.Keyframe;
import com.metrovoc.reprise.editor.OrbitInterpolator;
import com.metrovoc.reprise.editor.PathInterpolator;
import com.metrovoc.reprise.editor.ReplayProject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Moves the camera along the keyframed path while the path preview plays, and during export. */
public final class CameraPath {
    private static boolean preview;
    private static boolean forced;
    private static CameraPose current;
    private static Entity pathView;

    public static boolean preview() {
        return preview;
    }

    public static void setPreview(boolean value) {
        preview = value;
        if (!forced && !preview) releaseView();
    }

    /** Export drives the path regardless of the preview toggle. */
    public static void setForced(boolean value) {
        forced = value;
        if (!forced && !preview) releaseView();
    }

    private static void releaseView() {
        if (pathView == null) return;
        var mc = Minecraft.getInstance();
        if (mc.getCameraEntity() == pathView) mc.setCameraEntity(mc.player);
        pathView = null;
    }

    public static boolean ownsView() {
        return pathView != null && Minecraft.getInstance().getCameraEntity() == pathView;
    }

    public static boolean isDriving() {
        ReplaySession session = ReplaySession.active();
        Minecraft mc = Minecraft.getInstance();
        return session != null && mc.level != null && mc.player != null && !session.project().keyframes.isEmpty()
            && (mc.getCameraEntity() == mc.player || ownsView()) && (forced || preview);
    }

    /** The pose applied this frame, for roll and field of view; null when the path is not driving. */
    public static CameraPose current() {
        return current;
    }

    public static void frame(double tick, float partialTick) {
        if (!isDriving()) {
            releaseView();
            current = null;
            return;
        }
        var session = ReplaySession.active();
        ReplayProject project = session.project();
        Integer id = Keyframe.spectatorAt(project.keyframes, tick);
        if (id != null) {
            var mc = Minecraft.getInstance();
            Entity entity = mc.level.getEntity(id.intValue());
            if (entity != null && entity != mc.player) {
                if (mc.getCameraEntity() != entity) {
                    mc.setCameraEntity(entity);
                    PovHands.snap();
                }
                pathView = entity;
                // Keep the detached camera where the actor was last seen if it disappears.
                var eye = entity.getEyePosition(partialTick);
                mc.player.snapTo(eye.x, eye.y - mc.player.getEyeHeight(), eye.z,
                    entity.getViewYRot(partialTick), entity.getViewXRot(partialTick));
                mc.player.setOldPosAndRot();
            } else {
                releaseView();
            }
            current = null;
            return;
        }
        releaseView();
        current = evaluate(project.keyframes, tick, partialTick);
        apply(current);
    }

    /** Resolve the spline's neighbouring keys against the actors in this rendered frame. */
    public static CameraPose evaluate(List<Keyframe> keys, double tick, float partialTick) {
        if (keys.isEmpty()) {
            return null;
        }
        int left = PathInterpolator.segmentAt(keys, tick);
        int first = Math.max(0, left - 1);
        List<Keyframe> window = keys.subList(first, Math.min(keys.size(), left + 3));
        List<Keyframe> resolved = window;
        for (int i = 0; i < window.size(); i++) {
            Keyframe key = window.get(i);
            if (key.target != null) {
                if (resolved == window) {
                    resolved = new ArrayList<>(window);
                }
                resolved.set(i, resolve(key, partialTick));
            }
        }
        CameraPose pose = PathInterpolator.evaluate(resolved, tick);
        Keyframe a = keys.get(left), b = keys.get(Math.min(left + 1, keys.size() - 1));
        // On a shot with one subject, aim from the interpolated camera position even on a curved path.
        if (a.target != null && (tick <= a.tick || a.interpolation == Keyframe.Interpolation.HOLD
            || a.target.sameSubject(b.target))) {
            Vec3 subject = subject(a.target, partialTick);
            if (subject != null) {
                if (a != b && tick >= a.tick && OrbitInterpolator.sameOrbit(a, b)) {
                    return OrbitInterpolator.evaluate(keys, left, tick, subject.x, subject.y, subject.z, Minecraft.getInstance().player.getEyeHeight(), pose);
                }
                return pose.aimAt(subject.x, subject.y, subject.z, Minecraft.getInstance().player.getEyeHeight());
            }
        }
        return pose;
    }

    static Keyframe resolve(Keyframe key, float partialTick) {
        if (key.target == null) {
            return key;
        }
        Vec3 subject = subject(key.target, partialTick);
        if (subject == null) {
            return key;
        }
        Keyframe result = key.copy();
        if (key.target.mode() != Keyframe.Tracking.AIM) {
            result.x += subject.x - key.target.x();
            result.y += subject.y - key.target.y();
            result.z += subject.z - key.target.z();
        }
        CameraPose aimed = new CameraPose(result.x, result.y, result.z, key.yaw, key.pitch, key.roll, key.fov)
            .aimAt(subject.x, subject.y, subject.z, Minecraft.getInstance().player.getEyeHeight());
        result.yaw = aimed.yaw();
        result.pitch = aimed.pitch();
        return result;
    }

    private static Vec3 subject(Keyframe.Target target, float partialTick) {
        if (target.id() == null) {
            return new Vec3(target.x(), target.y(), target.z());
        }
        Entity e = Minecraft.getInstance().level.getEntity(target.id());
        if (e == null) {
            return null;
        }
        return anchor(e, target.point(), partialTick);
    }

    public static Vec3 anchor(Entity e, Keyframe.AimPoint point, float partialTick) {
        double height = switch (point) {
            case EYES -> e.getEyeHeight();
            case CENTER -> e.getBbHeight() * 0.5;
            case FEET -> 0;
        };
        return new Vec3(Mth.lerp(partialTick, e.xOld, e.getX()), Mth.lerp(partialTick, e.yOld, e.getY()) + height,
            Mth.lerp(partialTick, e.zOld, e.getZ()));
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
