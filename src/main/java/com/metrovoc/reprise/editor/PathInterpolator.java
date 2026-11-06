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

    public static int segmentAt(List<Keyframe> keys, double tick) {
        int low = 0, high = keys.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (keys.get(mid).tick <= tick) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

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
        int i = segmentAt(keys, tick);
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
        if (a.spline != null) {
            var curve = a.spline;
            return new CameraPose(curve.sample(0, a.x, b.x, s), curve.sample(1, a.y, b.y, s), curve.sample(2, a.z, b.z, s),
                (float) curve.sample(3, a.yaw, b.yaw, s), (float) curve.sample(4, a.pitch, b.pitch, s),
                (float) curve.sample(5, a.roll, b.roll, s), (float) (a.fov + s * (b.fov - a.fov)));
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

    static double wrap(double degrees) {
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
        if (k0.interpolation == Keyframe.Interpolation.HOLD) {
            k0 = k1;
            p0 = p1;
        }
        if (k2.interpolation == Keyframe.Interpolation.HOLD) {
            k3 = k2;
            p3 = p2;
        }
        return timeAwareCubic(s, k0.tick, p0, k1.tick, p1, k2.tick, p2, k3.tick, p3);
    }

    /** Repeat the nearest endpoint for a one-sided tangent at the start or end of a curve. */
    static double timeAwareCubic(double s, double t0, double p0, double t1, double p1,
                                 double t2, double p2, double t3, double p3) {
        double span = t2 - t1;
        double m1 = (p2 - p0) / (t2 - t0);
        double m2 = (p3 - p1) / (t3 - t1);
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
