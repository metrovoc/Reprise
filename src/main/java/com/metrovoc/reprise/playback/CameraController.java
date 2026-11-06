package com.metrovoc.reprise.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Flies the replay camera in real time, every frame, so it moves smoothly whatever the replay's speed, and also while
 * it is paused. The scroll wheel sets the speed, as in spectator mode.
 */
public final class CameraController {
    private static long lastNanos;
    private static Vec3 velocity = Vec3.ZERO;
    private static float roll;

    public static float roll() {
        return roll;
    }

    public static void setRoll(float value) {
        roll = value;
    }

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        LocalPlayer camera = mc.player;
        if (camera == null || mc.getCameraEntity() != camera || CameraPath.isDriving() || com.metrovoc.reprise.export.VideoExporter.isRunning()) {
            velocity = Vec3.ZERO;
            return;
        }
        boolean input = mc.screen == null || mc.screen instanceof com.metrovoc.reprise.editor.EditorScreen editor && editor.flying();
        double forward = 0, strafe = 0, up = 0;
        if (input) {
            forward = axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
            strafe = axis(mc.options.keyLeft.isDown(), mc.options.keyRight.isDown());
            up = axis(mc.options.keyJump.isDown(), mc.options.keyShift.isDown());
            if (com.metrovoc.reprise.RepriseKeys.ROLL_LEFT.isDown()) {
                roll -= (float) (45 * dt);
            }
            if (com.metrovoc.reprise.RepriseKeys.ROLL_RIGHT.isDown()) {
                roll += (float) (45 * dt);
            }
        }
        double speed = camera.getAbilities().getFlyingSpeed() * 200.0 * (mc.options.keySprint.isDown() ? 2.5 : 1.0);
        float yaw = camera.getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
        Vec3 wish = new Vec3(strafe * cos - forward * sin, up, forward * cos + strafe * sin);
        if (wish.lengthSqr() > 1) {
            wish = wish.normalize();
        }
        wish = wish.scale(speed);
        double blend = 1 - Math.exp(-dt * 12);
        velocity = velocity.add(wish.subtract(velocity).scale(blend));
        if (velocity.lengthSqr() < 1e-6 && wish.lengthSqr() == 0) {
            velocity = Vec3.ZERO;
            return;
        }
        Vec3 pos = camera.position().add(velocity.scale(dt));
        camera.setPos(pos);
        camera.setOldPosAndRot(pos, camera.yRotO, camera.xRotO);
    }

    private static double axis(boolean positive, boolean negative) {
        return (positive ? 1 : 0) - (negative ? 1 : 0);
    }

    private CameraController() {}
}
