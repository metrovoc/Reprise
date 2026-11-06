package com.metrovoc.reprise.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathInterpolatorTest {
    private static Keyframe key(double tick, double x, float yaw) {
        Keyframe k = new Keyframe();
        k.tick = tick;
        k.x = x;
        k.yaw = yaw;
        return k;
    }

    @Test
    void passesThroughKeyframes() {
        List<Keyframe> keys = List.of(key(0, 0, 0), key(20, 10, 90), key(60, 0, 180), key(80, 5, 0));
        for (Keyframe k : keys) {
            CameraPose p = PathInterpolator.evaluate(keys, k.tick);
            assertEquals(k.x, p.x(), 1e-9);
            assertEquals(0, Math.floorMod(Math.round(p.yaw() - k.yaw), 360));
        }
    }

    @Test
    void evenlySpacedPointsOnALineMoveAtConstantSpeed() {
        List<Keyframe> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            keys.add(key(i * 10, i * 3, 0));
        }
        for (double t = 0; t <= 40; t += 0.5) {
            assertEquals(t * 0.3, PathInterpolator.evaluate(keys, t).x(), 1e-9);
        }
    }

    @Test
    void unevenSpacingKeepsSpeedContinuousAtKeyframes() {
        // 10 blocks in 10 ticks, then 10 blocks in 40 ticks: the velocity must not jump at the middle keyframe.
        List<Keyframe> keys = List.of(key(0, 0, 0), key(10, 10, 0), key(50, 20, 0));
        double h = 1e-4;
        double before = (PathInterpolator.evaluate(keys, 10).x() - PathInterpolator.evaluate(keys, 10 - h).x()) / h;
        double after = (PathInterpolator.evaluate(keys, 10 + h).x() - PathInterpolator.evaluate(keys, 10).x()) / h;
        assertEquals(before, after, 1e-3);
    }

    @Test
    void turnsTheShortWayRound() {
        List<Keyframe> keys = List.of(key(0, 0, 170), key(10, 0, -170));
        float mid = PathInterpolator.evaluate(keys, 5).yaw();
        assertEquals(180, Math.floorMod(Math.round(mid), 360));
    }

    @Test
    void holdStaysThenCuts() {
        Keyframe a = key(0, 0, 0);
        a.interpolation = Keyframe.Interpolation.HOLD;
        List<Keyframe> keys = List.of(a, key(10, 10, 0));
        assertEquals(0, PathInterpolator.evaluate(keys, 9.9).x(), 1e-9);
        assertEquals(10, PathInterpolator.evaluate(keys, 10).x(), 1e-9);
    }

    @Test
    void easeStartsAndEndsSlowly() {
        Keyframe a = key(0, 0, 0);
        a.interpolation = Keyframe.Interpolation.EASE;
        List<Keyframe> keys = List.of(a, key(10, 10, 0));
        double early = PathInterpolator.evaluate(keys, 1).x();
        assertTrue(early < 1, "ease-in moves less than linear early on: " + early);
        assertEquals(5, PathInterpolator.evaluate(keys, 5).x(), 1e-9);
    }
}
