package com.metrovoc.reprise.editor;

/** Replay speed from this tick on: below 1 is slow motion, above 1 a timelapse. Speeds between keys blend linearly. */
public final class SpeedKey {
    public double tick;
    public float speed = 1;

    public SpeedKey() {}

    public SpeedKey(double tick, float speed) {
        this.tick = tick;
        this.speed = speed;
    }
}
