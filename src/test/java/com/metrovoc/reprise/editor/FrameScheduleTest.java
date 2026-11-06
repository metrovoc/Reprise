package com.metrovoc.reprise.editor;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameScheduleTest {
    @Test void abruptRampCrossesBothKeysWithinOneFrame() {
        var keys = List.of(new SpeedKey(10, 1), new SpeedKey(11, 16), new SpeedKey(12, 0.25F));
        var schedule = new FrameSchedule(keys, 0, 30, 30);
        double rampUp = Math.log(16) / 15;
        double rampDown = Math.log(64) / 15.75;
        assertEquals(124, schedule.frameCount());
        assertEquals(10 + rampUp, schedule.videoTimeAt(11), 1e-12);
        assertEquals(10 + rampUp + rampDown, schedule.videoTimeAt(12), 1e-12);
        assertEquals(12 + (16 * 20.0 / 30 - 10 - rampUp - rampDown) * 0.25, schedule.tickAt(16), 1e-12);
        for (long frame = 0; frame < schedule.frameCount(); frame++) {
            assertEquals(frame * 20.0 / 30, schedule.videoTimeAt(schedule.tickAt(frame)), 1e-10);
        }
    }

    @Test void rangeBeginningAndEndingInsideRampUsesLocalSpeeds() {
        var schedule = new FrameSchedule(List.of(new SpeedKey(0, 1), new SpeedKey(100, 3)), 25, 75, 120);
        assertEquals(25, schedule.tickAt(0));
        assertEquals(50 * Math.log(2.5 / 1.5), schedule.videoTimeAt(75), 1e-12);
        assertEquals(25 + 75 * Math.expm1(0.02), schedule.tickAt(6), 1e-12);
    }

    @Test void longSlowMotionCanAddressFramesBeyondTheOldCutoff() {
        var schedule = new FrameSchedule(List.of(new SpeedKey(0, 0.125F)), 0, 288000, 120);
        assertEquals(13_824_001, schedule.frameCount());
        assertEquals(288000, schedule.tickAt(schedule.frameCount() - 1), 1e-9);
    }

    @Test void fractionalSpeedKeysInsideOneTickShareThePreviewClock() {
        var p = new ReplayProject();
        p.speedKeys.addAll(List.of(new SpeedKey(0, 1), new SpeedKey(0.5, 16), new SpeedKey(1, 1)));
        assertEquals(50 * Math.log(16) / 15, p.previewMillisPerTick(0, 10), 1e-6);
        p.speedKeys.clear();
        p.sort();
        assertEquals(50, p.previewMillisPerTick(0, 10));
    }

    @Test void shallowLongRampRetainsSubtickPrecision() {
        var schedule = new FrameSchedule(List.of(new SpeedKey(0, 1), new SpeedKey(1_000_000_000, Math.nextUp(1f))),
            0, 1_000_000_000, 20);
        assertEquals(1, schedule.videoTimeAt(1), 1e-14);
        assertEquals(1, schedule.tickAt(1), 1e-14);
    }

    @Test void emptyAndSingleInstantRanges() {
        assertEquals(0, new FrameSchedule(List.of(), 3, 2, 30).frameCount());
        var single = new FrameSchedule(List.of(), 3, 3, 30);
        assertEquals(1, single.frameCount());
        assertEquals(3, single.tickAt(0));
        assertThrows(IndexOutOfBoundsException.class, () -> single.tickAt(1));
    }
}
