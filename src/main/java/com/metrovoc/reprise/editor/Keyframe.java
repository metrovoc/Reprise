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

    /** Replay time in ticks; fractional ticks are allowed. */
    public double tick;
    public double x, y, z;
    public float yaw, pitch, roll;
    public float fov = 70;
    /** How the camera travels from this keyframe to the next. */
    public Interpolation interpolation = Interpolation.SMOOTH;

    public Keyframe copy() {
        Keyframe k = new Keyframe();
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
