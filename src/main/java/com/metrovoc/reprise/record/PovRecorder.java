package com.metrovoc.reprise.record;

import com.metrovoc.reprise.format.PovSamples;
import com.metrovoc.reprise.format.PovSamples.Pose;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Resamples the local player's rendered movement onto the replay's evenly spaced POV samples. */
final class PovRecorder {
    private final Pose[] samples;
    private LocalPlayer player;
    private Pose previous;
    private float previousTime;
    private int next;

    PovRecorder(int divisions) {
        samples = new Pose[divisions + 1];
    }

    void reset() {
        player = null;
    }

    void frame(LocalPlayer current, float partial) {
        if (player != null && current == player && partial > previousTime && partial < 1) {
            append(partial, pose(current, partial));
        }
    }

    byte[] finish(LocalPlayer current) {
        // Immediately after a tick Minecraft starts rendering the next interval at partial zero.
        Pose boundary = pose(current, 0);
        byte[] bytes = null;
        if (current == player) {
            append(1, boundary);
            for (int i = 1; i < samples.length; i++) {
                if (!samples[0].equals(samples[i])) {
                    bytes = PovSamples.encode(player.getId(), samples);
                    break;
                }
            }
        }
        player = current;
        previous = samples[0] = boundary;
        previousTime = 0;
        next = 1;
        return bytes;
    }

    private void append(float time, Pose pose) {
        while (next < samples.length) {
            double target = (double) next / (samples.length - 1);
            if (target > time) break;
            samples[next++] = target == time || previous.equals(pose) ? pose
                : previous.interpolate(pose, (target - previousTime) / (time - previousTime));
        }
        previous = pose;
        previousTime = time;
    }

    private static Pose pose(LocalPlayer player, float partial) {
        return new Pose(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()),
            Mth.lerp(partial, player.zo, player.getZ()), Mth.wrapDegrees(player.getViewYRot(partial)), Mth.wrapDegrees(player.getViewXRot(partial)));
    }
}
