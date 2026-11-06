package com.metrovoc.reprise.editor;

import java.util.List;

/**
 * Evaluates the camera path at any time. Each channel (position, angles, field of view) is a piecewise cubic Hermite
 * curve. Its tangents come from the neighbouring keyframes and are scaled by the keyframes' spacing in time, so the
 * camera keeps an even speed through keyframes that are unevenly spaced (a time-aware Catmull-Rom spline). Angles are
 * unwrapped first, so the camera always turns the short way round.
 */
public final class PathInterpolator {
    private PathInterpolator() {}

    public static CameraPose evaluate(List<Keyframe> keys, double tick) {
        int n = keys.size();
        if (n == 0) {
            return null;
        }
        if (n == 1 || tick <= keys.get(0).tick) {
            return pose(keys.get(0));
        }
        if (tick >= keys.get(n - 1).tick) {
            return pose(keys.get(n - 1));
        }
        int i = 0;
        while (i < n - 2 && keys.get(i + 1).tick <= tick) {
            i++;
        }
        Keyframe a = keys.get(i), b = keys.get(i + 1);
        double span = b.tick - a.tick;
        double s = span <= 0 ? 1 : (tick - a.tick) / span;
        switch (a.interpolation) {
            case HOLD -> {
                return pose(a);
            }
            case LINEAR -> {
                return lerpPose(keys, i, s);
            }
            case EASE -> {
                return lerpPose(keys, i, s * s * (3 - 2 * s));
            }
            default -> {
            }
        }
        double[] yaws = unwrapped(keys, i, k -> k.yaw);
        double[] rolls = unwrapped(keys, i, k -> k.roll);
        return new CameraPose(
            channel(keys, i, s, k -> k.x, null),
            channel(keys, i, s, k -> k.y, null),
            channel(keys, i, s, k -> k.z, null),
            (float) channel(keys, i, s, null, yaws),
            (float) channel(keys, i, s, k -> k.pitch, null),
            (float) channel(keys, i, s, null, rolls),
            (float) channel(keys, i, s, k -> k.fov, null));
    }

    private interface Getter {
        double get(Keyframe k);
    }

    /** Values of an angle at keys i-1..i+2, unwrapped relative to key i. */
    private static double[] unwrapped(List<Keyframe> keys, int i, Getter g) {
        double[] out = new double[4];
        double prev = g.get(keys.get(i));
        out[1] = prev;
        for (int j = 2; j < 4; j++) {
            int idx = Math.min(keys.size() - 1, i + j - 1);
            prev = prev + wrap(g.get(keys.get(idx)) - prev);
            out[j] = prev;
        }
        int before = Math.max(0, i - 1);
        out[0] = out[1] + wrap(g.get(keys.get(before)) - out[1]);
        return out;
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) {
            degrees -= 360;
        }
        if (degrees < -180) {
            degrees += 360;
        }
        return degrees;
    }

    private static double channel(List<Keyframe> keys, int i, double s, Getter g, double[] values) {
        int n = keys.size();
        Keyframe k1 = keys.get(i), k2 = keys.get(i + 1);
        Keyframe k0 = keys.get(Math.max(0, i - 1)), k3 = keys.get(Math.min(n - 1, i + 2));
        double p0 = values != null ? values[0] : g.get(k0);
        double p1 = values != null ? values[1] : g.get(k1);
        double p2 = values != null ? values[2] : g.get(k2);
        double p3 = values != null ? values[3] : g.get(k3);
        double t0 = k0.tick, t1 = k1.tick, t2 = k2.tick, t3 = k3.tick;
        double span = t2 - t1;
        // Tangents in value per tick; one-sided at the ends of the path, and flat next to a HOLD.
        double m1 = (i == 0 || k0.interpolation == Keyframe.Interpolation.HOLD) ? (p2 - p1) / span : (p2 - p0) / (t2 - t0);
        double m2 = (i + 2 >= n || k2.interpolation == Keyframe.Interpolation.HOLD) ? (p2 - p1) / span : (p3 - p1) / (t3 - t1);
        double s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
        return h00 * p1 + h10 * span * m1 + h01 * p2 + h11 * span * m2;
    }

    private static CameraPose lerpPose(List<Keyframe> keys, int i, double s) {
        Keyframe a = keys.get(i), b = keys.get(i + 1);
        return new CameraPose(
            a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s,
            (float) (a.yaw + wrap(b.yaw - a.yaw) * s), (float) (a.pitch + (b.pitch - a.pitch) * s),
            (float) (a.roll + wrap(b.roll - a.roll) * s), (float) (a.fov + (b.fov - a.fov) * s));
    }

    private static CameraPose pose(Keyframe k) {
        return new CameraPose(k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov);
    }
}
