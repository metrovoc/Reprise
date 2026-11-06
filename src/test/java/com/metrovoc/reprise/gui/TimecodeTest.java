package com.metrovoc.reprise.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimecodeTest {
    @Test void outputIncludesLastFrameAndRoundsToMilliseconds() {
        assertEquals("0:00.017", Timecode.outputDuration(1, 60));
        assertEquals("0:00.433", Timecode.outputDuration(13, 30));
        assertEquals("1:00:00.000", Timecode.outputDuration(86400, 24));
    }

    @Test void timelineTenthsCarryIntoTheNextMinute() {
        assertEquals("0:59.9", Timecode.precise(1198));
        assertEquals("1:00.0", Timecode.precise(1199));
    }
}
