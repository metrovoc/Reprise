package com.metrovoc.reprise.editor;

/** A camera pose at a moment of the replay. */
public final class Keyframe {
    public enum Interpolation {
        /** A smooth curve through the neighbouring keyframes. */
        SMOOTH,
        /** Straight lines at constant speed. */
        LINEAR,
        /** Starts and stops gently. */
        EASE,
        /** Stays here until the next keyframe, then cuts. */
        HOLD
    }

    public enum Tracking { AIM, FOLLOW, ORBIT }
    public enum AimPoint { EYES, CENTER, FEET }
    /** The authored anchor in world coordinates. A null ID targets a fixed point. */
    public record Target(java.util.UUID id, String name, Tracking mode, AimPoint point, double x, double y, double z) {
        public boolean sameSubject(Target other) {
            return other != null && java.util.Objects.equals(id, other.id) && point == other.point;
        }
    }
    /** Endpoint tangent offsets from a straight chord, for an authored cubic in XYZ, yaw, pitch and roll. */
    public record Spline(double[] startOffset, double[] endOffset) {
        double sample(int channel, double a, double b, double s) {
            return a + s * (b - a) + s * (1 - s) * ((1 - s) * startOffset[channel] + s * endOffset[channel]);
        }
    }
    /** Recorded entity viewed exactly at this key, and during the interval after it. Null selects the camera pose. */
    public Integer spectatorAtKey, spectatorAfterKey;

    public static Integer spectatorAt(java.util.List<Keyframe> keys, double tick) {
        var key = keys.get(PathInterpolator.segmentAt(keys, tick));
        if (tick < key.tick) return null;
        return tick == key.tick ? key.spectatorAtKey : key.spectatorAfterKey;
    }

    public Spline spline;
    public Target target;
    /** Extra full revolutions from this orbit key to the next. */
    public int turns;

    /** Replay time in ticks; fractional ticks are allowed. */
    public double tick;
    public double x, y, z;
    public float yaw, pitch, roll;
    public float fov = 70;
    /** How the camera travels from this keyframe to the next. */
    public Interpolation interpolation = Interpolation.SMOOTH;

    public Keyframe copy() {
        Keyframe k = new Keyframe();
        k.spectatorAtKey = spectatorAtKey;
        k.spectatorAfterKey = spectatorAfterKey;
        k.spline = this.spline;
        k.target = this.target;
        k.turns = this.turns;
        k.tick = this.tick;
        k.x = this.x;
        k.y = this.y;
        k.z = this.z;
        k.yaw = this.yaw;
        k.pitch = this.pitch;
        k.roll = this.roll;
        k.fov = this.fov;
        k.interpolation = this.interpolation;
        return k;
    }
}
