package com.metrovoc.reprise.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.EnumSet;

/** Visual overrides saved with a replay project. Clock values use Minecraft's 24,000-tick day. */
public final class EnvironmentSettings {
    public static final int DAY = 24000;
    public enum Clock { RECORDED, FIXED, KEYFRAMED }
    public enum Weather {
        RECORDED, CLEAR, OVERCAST, RAIN, SNOW, THUNDER;

        public boolean hidesLightning() {
            return this != RECORDED && this != THUNDER;
        }
    }
    public enum TimeCurve { FORWARD, LINEAR, EASE_IN, EASE_OUT, EASE, HOLD, SMOOTH }
    public record TimeKey(double tick, int time, TimeCurve curve) {
        public TimeKey {
            if (curve == null) curve = TimeCurve.FORWARD; // Projects saved before curves were editable.
        }

        public TimeKey(double tick, int time) { this(tick, time, TimeCurve.FORWARD); }
    }

    public enum Layer { TERRAIN, PLAYERS, ENTITIES, PARTICLES, CLOUDS }
    public record Fog(float start, float end, Integer color) {}

    public EnumSet<Layer> hidden = EnumSet.noneOf(Layer.class);
    public boolean nightVision;
    public Integer skyColor;
    public Fog fog;
    public Clock clock = Clock.RECORDED;
    public Weather weather = Weather.RECORDED;
    public int fixedTime = 6000;
    public List<TimeKey> keys = new ArrayList<>();

    public TimeKey keyAt(double tick) {
        return keys.stream().filter(k -> k.tick == tick).findFirst().orElse(null);
    }

    public void key(double tick, int time) {
        TimeKey old = keyAt(tick);
        key(tick, time, old == null ? TimeCurve.FORWARD : old.curve);
    }

    public void key(double tick, int time, TimeCurve curve) {
        keys.removeIf(k -> k.tick == tick);
        keys.add(new TimeKey(tick, time, curve));
        keys.sort(Comparator.comparingDouble(TimeKey::tick));
        clock = Clock.KEYFRAMED;
    }

    /** Returns -1 for the recorded clock. Clock readings wrap at midnight. */
    public double timeAt(double tick) {
        if (clock == Clock.FIXED) {
            return fixedTime;
        }
        if (clock == Clock.RECORDED || keys.isEmpty()) {
            return -1;
        }
        int low = 0, high = keys.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (keys.get(mid).tick <= tick) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        TimeKey a = keys.get(low);
        if (tick <= a.tick || low + 1 == keys.size()) {
            return Math.floorMod(a.time, DAY);
        }
        TimeKey b = keys.get(low + 1);
        double progress = (tick - a.tick) / (b.tick - a.tick);
        if (a.curve == TimeCurve.SMOOTH) {
            TimeKey before = keys.get(Math.max(0, low - 1));
            if (before.curve == TimeCurve.HOLD) before = a;
            TimeKey after = b.curve == TimeCurve.HOLD ? b : keys.get(Math.min(keys.size() - 1, low + 2));
            return wrapTime(PathInterpolator.timeAwareCubic(progress, before.tick, before.time, a.tick, a.time,
                b.tick, b.time, after.tick, after.time));
        }
        double delta = (double) b.time - a.time;
        switch (a.curve) {
            case FORWARD -> delta = Math.floorMod((long) b.time - a.time, DAY);
            case EASE_IN -> progress = progress * progress * progress;
            case EASE_OUT -> progress = 1 - Math.pow(1 - progress, 3);
            case EASE -> progress = progress < 0.5 ? 4 * progress * progress * progress : 1 - 4 * Math.pow(1 - progress, 3);
            case HOLD -> progress = 0;
            case LINEAR -> { }
        }
        return wrapTime(a.time + progress * delta);
    }

    private static double wrapTime(double time) {
        return time - DAY * Math.floor(time / DAY);
    }
}
