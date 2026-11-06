package com.metrovoc.reprise.editor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CameraShakeTest {
    private static CameraShake.Key key(double tick, double rate, boolean hold) {
        return new CameraShake.Key(tick, rate, 3, 1, 0, hold ? CameraShake.Curve.HOLD : CameraShake.Curve.LINEAR);
    }

    @Test void linearFrequencyAccumulatesPhaseInVideoSeconds() {
        var clock = new FrameSchedule(List.of(new SpeedKey(0, .5)), 0, 80, 80);
        var ramp = new CameraShake(List.of(key(10, 1, false), key(30, 5, false)), clock);
        var constant = new CameraShake(List.of(key(10, 1, false)), clock);
        // One video second from 10 to 20, increasing from 1 Hz to 3 Hz: two cycles.
        assertEquals(constant.sample(30), ramp.sample(20));
        // After two seconds the integrated ramp has six cycles, independent of previous samples.
        assertEquals(constant.sample(70), ramp.sample(30));
        assertEquals(3, ramp.settingsAt(20).horizontalHz());
        assertEquals(0, ramp.sample(19.5).y);
        assertEquals(0, ramp.sample(9).length());
        assertNotEquals(0, ramp.sample(19.5).x);
    }

    @Test void easedPhaseMatchesIntegratedFrequency() {
        var clock = new FrameSchedule(List.of(), 0, 200, 20);
        var constant = new CameraShake(List.of(key(0, 1, false)), clock);
        for (var curve : CameraShake.Curve.values()) {
            var a = new CameraShake.Key(0, 1, 3, 1, 0, curve);
            var ramp = new CameraShake(List.of(a, key(20, 5, false)), clock);
            double cycles = 0;
            for (int i = 0; i < 20; i++) {
                for (int j = 0; j < 100; j++) {
                    cycles += ramp.settingsAt(i + (j + 0.5) / 100).horizontalHz() / 2000;
                }
                assertEquals(constant.sample(cycles * 20).x, ramp.sample(i + 1).x, .0001);
            }
        }
    }

    @Test void heldRateChangesOnlyAtTheNextKeyAndPreservesPhase() {
        var clock = new FrameSchedule(List.of(), 0, 200, 20);
        var held = new CameraShake(List.of(key(0, 2, true), key(20, 5, true)), clock);
        var constant = new CameraShake(List.of(key(0, 2, true)), clock);
        assertEquals(constant.sample(19.25), held.sample(19.25));
        assertEquals(constant.sample(20), held.sample(20));
        assertEquals(0, constant.sample(30).distance(held.sample(24)), 1e-6);
        assertEquals(2, held.settingsAt(19.25).horizontalHz());
        assertEquals(5, held.settingsAt(20).horizontalHz());
    }
}
