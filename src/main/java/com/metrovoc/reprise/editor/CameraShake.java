package com.metrovoc.reprise.editor;

import java.util.Arrays;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.joml.Vector2f;

/** Camera-local yaw and pitch in degrees, sampled at an absolute time on the edited shot. */
public final class CameraShake {
    public enum Curve {
        LINEAR, HOLD, EASE_IN, EASE_OUT, EASE_IN_OUT;

        double weight(double t) {
            return switch (this) {
                case HOLD -> 0;
                case LINEAR -> t;
                case EASE_IN -> t * t * t;
                case EASE_OUT -> 1 - Math.pow(1 - t, 3);
                case EASE_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - 4 * Math.pow(1 - t, 3);
            };
        }

        // Average curve weight from 0 to t. Multiplying by elapsed time integrates the rate.
        double mean(double t) {
            return switch (this) {
                case HOLD -> 0;
                case LINEAR -> t / 2;
                case EASE_IN -> t * t * t / 4;
                case EASE_OUT -> t * (1.5 - t + t * t / 4);
                case EASE_IN_OUT -> t < 0.5 ? t * t * t : (t - 0.5 + Math.pow(1 - t, 4)) / t;
            };
        }
    }

    public record Key(double tick, double horizontalHz, double horizontalDegrees, double verticalHz,
                      double verticalDegrees, Curve curve) {
        Key at(double tick) {
            return new Key(tick, horizontalHz, horizontalDegrees, verticalHz, verticalDegrees, curve);
        }
    }

    private static final SimplexNoise HORIZONTAL = new SimplexNoise(RandomSource.create(37));
    private static final SimplexNoise VERTICAL = new SimplexNoise(RandomSource.create(71));
    private final List<Key> keys;
    private final FrameSchedule clock;
    private final double[] seconds, horizontalPhase, verticalPhase;

    CameraShake(List<Key> keys, FrameSchedule clock) {
        this.keys = List.copyOf(keys);
        this.clock = clock;
        seconds = new double[keys.size()];
        horizontalPhase = new double[keys.size()];
        verticalPhase = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            seconds[i] = clock.videoTimeAt(key.tick) / 20;
            if (i == 0) continue;
            Key prior = keys.get(i - 1);
            double elapsed = seconds[i] - seconds[i - 1];
            horizontalPhase[i] = horizontalPhase[i - 1] + cycles(elapsed, prior.horizontalHz, key.horizontalHz, prior.curve.mean(1));
            verticalPhase[i] = verticalPhase[i - 1] + cycles(elapsed, prior.verticalHz, key.verticalHz, prior.curve.mean(1));
        }
    }

    private int indexAt(double now) {
        int found = Arrays.binarySearch(seconds, now);
        return found < 0 ? -found - 2 : found;
    }

    public Key settingsAt(double tick) {
        double now = clock.videoTimeAt(tick) / 20;
        int index = indexAt(now);
        if (index < 0) return new Key(tick, 1, 1, 1, 1, Curve.LINEAR);
        Key a = keys.get(index);
        if (index + 1 == keys.size()) return a.at(tick);
        Key b = keys.get(index + 1);
        double t = a.curve.weight((now - seconds[index]) / (seconds[index + 1] - seconds[index]));
        return new Key(tick, lerp(a.horizontalHz, b.horizontalHz, t), lerp(a.horizontalDegrees, b.horizontalDegrees, t),
            lerp(a.verticalHz, b.verticalHz, t), lerp(a.verticalDegrees, b.verticalDegrees, t), a.curve);
    }

    public Vector2f sample(double tick) {
        double now = clock.videoTimeAt(tick) / 20;
        int index = indexAt(now);
        if (index < 0) return new Vector2f();
        Key a = keys.get(index), b = keys.get(Math.min(keys.size() - 1, index + 1));
        double elapsed = now - seconds[index];
        double t = index + 1 == keys.size() ? 0 : elapsed / (seconds[index + 1] - seconds[index]);
        double mean = a.curve.mean(t);
        double weight = a.curve.weight(t);
        double x = horizontalPhase[index] + cycles(elapsed, a.horizontalHz, b.horizontalHz, mean);
        double y = verticalPhase[index] + cycles(elapsed, a.verticalHz, b.verticalHz, mean);
        return new Vector2f((float) (HORIZONTAL.getValue(x, 0) * lerp(a.horizontalDegrees, b.horizontalDegrees, weight)),
            (float) (VERTICAL.getValue(0, y) * lerp(a.verticalDegrees, b.verticalDegrees, weight)));
    }

    // A trimmed or reordered render uses the same accumulated phase as the full shot.
    private static double cycles(double seconds, double from, double to, double mean) {
        return seconds * lerp(from, to, mean);
    }

    private static double lerp(double from, double to, double t) { return from + (to - from) * t; }
}
