package com.metrovoc.reprise.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimelineTest {
    @Test void zoomAtPointerPanToEdgesAndReopen() {
        var timeline = new Timeline();
        timeline.setBounds(10, 20, 800, 20);
        int hour = 72000;
        double anchor = timeline.tickAt(610, hour);
        timeline.zoom(610, 30, hour);
        assertEquals(anchor, timeline.tickAt(610, hour), 1e-6);
        assertEquals(80, timeline.xOf(anchor + 1, hour) - timeline.xOf(anchor, hour));
        timeline.pan(-160, hour);
        assertEquals(anchor + 2, timeline.tickAt(610, hour), 1e-6);
        var saved = timeline.view(hour);
        timeline.fit();
        assertNull(timeline.view(hour));
        timeline.restore(saved, hour);
        assertEquals(anchor + 2, timeline.tickAt(610, hour), 1e-6);
        timeline.pan(1e9, hour);
        assertEquals(0, timeline.tickAt(10, hour));
        timeline.pan(-1e9, hour);
        assertEquals(hour, timeline.tickAt(810, hour));
        timeline.zoom(810, -40, hour);
        assertNull(timeline.view(hour));
        assertEquals(0, timeline.tickAt(10, hour));
    }

    @Test void shortAndEmptyRecordingsKeepTheirWholeRange() {
        var timeline = new Timeline();
        timeline.setBounds(10, 0, 100, 20);
        timeline.zoom(50, 15, 3);
        assertNull(timeline.view(3));
        assertEquals(3, timeline.tickAt(200, 3));
        timeline.pan(-200, 0);
        assertEquals(0, timeline.tickAt(50, 0));
    }
}
