package com.metrovoc.reprise.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EnvironmentSettingsTest {
    @Test void keysClampOutsideTheRangeAndAdvanceThroughMidnight() {
        var settings = new EnvironmentSettings();
        settings.key(30, 1000);
        settings.key(10, 23000);
        assertEquals(23000, settings.timeAt(0));
        assertEquals(23950, settings.timeAt(19.5));
        assertEquals(0, settings.timeAt(20));
        assertEquals(1000, settings.timeAt(40));
        settings.key(10, 3000);
        assertEquals(2, settings.keys.size());
        assertEquals(14000, settings.timeAt(20));
    }

    @Test void recordedAndFixedModesPreserveAuthoredKeys() {
        var settings = new EnvironmentSettings();
        settings.key(10, 1000);
        settings.clock = EnvironmentSettings.Clock.FIXED;
        settings.fixedTime = 12000;
        assertEquals(12000, settings.timeAt(10));
        settings.clock = EnvironmentSettings.Clock.RECORDED;
        assertEquals(-1, settings.timeAt(10));
        settings.clock = EnvironmentSettings.Clock.KEYFRAMED;
        assertEquals(1000, settings.timeAt(10));
    }
}
