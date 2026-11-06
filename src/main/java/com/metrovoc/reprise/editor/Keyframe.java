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
