package com.metrovoc.reprise.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpeedCurveTest {
    @Test
    void noKeysIsRealTime() {
        ReplayProject p = new ReplayProject();
        double[] ticks = p.frameTicks(0, 200, 20);
        assertEquals(201, ticks.length);
        assertEquals(200, ticks[200], 1e-9);
    }

    @Test
    void halfSpeedDoublesTheFrames() {
        ReplayProject p = new ReplayProject();
        p.speedKeys.add(new SpeedKey(0, 0.5F));
        assertEquals(401, p.frameTicks(0, 200, 20).length);
    }

    @Test
    void rampIsIntegratedAccurately() {
        // Speed ramps from 1 to 3 over 100 ticks: time taken is integral of 1/v = 50 ln 3 ticks of video.
        ReplayProject p = new ReplayProject();
        p.speedKeys.add(new SpeedKey(0, 1));
        p.speedKeys.add(new SpeedKey(100, 3));
        int fps = 600;
        double[] ticks = p.frameTicks(0, 100, fps);
        double videoTicks = (ticks.length - 1) * 20.0 / fps;
        assertEquals(50 * Math.log(3), videoTicks, 0.1);
    }
}
