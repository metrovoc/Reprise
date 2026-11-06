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
    public record TimeKey(int tick, int time) {}

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

    public void key(int tick, int time) {
        keys.removeIf(k -> k.tick == tick);
        keys.add(new TimeKey(tick, time));
        keys.sort(Comparator.comparingInt(TimeKey::tick));
        clock = Clock.KEYFRAMED;
    }

    /** Returns -1 for the recorded clock. Keyframed time advances forward through midnight. */
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
            return a.time;
        }
        TimeKey b = keys.get(low + 1);
        double progress = (tick - a.tick) / (b.tick - a.tick);
        return (a.time + progress * Math.floorMod(b.time - a.time, DAY)) % DAY;
    }
}
