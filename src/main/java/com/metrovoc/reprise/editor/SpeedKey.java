package com.metrovoc.reprise.editor;

/** Replay speed from this tick on: below 1 is slow motion, above 1 a timelapse. Each interval can hold its starting speed or blend linearly. */
public final class SpeedKey {
    public double tick;
    public double speed = 1;
    /** Keep this speed up to the next key (a linear ReplayMod time segment). */
    public boolean hold;

    public SpeedKey() {}

    public SpeedKey(double tick, double speed) {
        this.tick = tick;
        this.speed = speed;
    }
}
