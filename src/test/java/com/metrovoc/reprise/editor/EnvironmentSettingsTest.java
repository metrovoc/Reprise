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

    @Test void fractionalKeysCanReverseHoldEaseAndSpanMultipleDays() {
        var settings = new EnvironmentSettings();
        settings.key(10.25, 18000, EnvironmentSettings.TimeCurve.LINEAR);
        settings.key(11.25, -6000);
        assertEquals(12000, settings.timeAt(10.5));
        assertEquals(0, settings.timeAt(11));
        settings.key(10.25, 42000);
        assertEquals(6000, settings.timeAt(10.5));
        settings.key(10.25, 18000, EnvironmentSettings.TimeCurve.EASE);
        assertEquals(16500, settings.timeAt(10.5));
        settings.key(10.25, 18000, EnvironmentSettings.TimeCurve.EASE_IN);
        assertEquals(17625, settings.timeAt(10.5));
        settings.key(10.25, 18000, EnvironmentSettings.TimeCurve.EASE_OUT);
        assertEquals(4125, settings.timeAt(10.5));
        settings.key(10.25, 6000, EnvironmentSettings.TimeCurve.HOLD);
        assertEquals(6000, settings.timeAt(11.249));
        assertEquals(18000, settings.timeAt(11.25));
        var legacy = new com.google.gson.Gson().fromJson("{\"tick\":7,\"time\":100}", EnvironmentSettings.TimeKey.class);
        assertEquals(EnvironmentSettings.TimeCurve.FORWARD, legacy.curve());
    }

    @Test void smoothKeysShareTangentsAcrossUnequalIntervals() {
        var settings = new EnvironmentSettings();
        settings.key(0, 6000, EnvironmentSettings.TimeCurve.SMOOTH);
        settings.key(4, 8000, EnvironmentSettings.TimeCurve.SMOOTH);
        settings.key(16, 17000, EnvironmentSettings.TimeCurve.SMOOTH);
        double epsilon = .00001;
        assertEquals(687.5, (settings.timeAt(4 + epsilon) - settings.timeAt(4 - epsilon)) / (2 * epsilon), .001);
        assertEquals(6000, settings.timeAt(0));
        assertEquals(17000, settings.timeAt(16));
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
