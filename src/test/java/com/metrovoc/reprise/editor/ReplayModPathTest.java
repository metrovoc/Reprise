package com.metrovoc.reprise.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayModPathTest {
    private static JsonArray shot() {
        return JsonParser.parseString("""
            [
              {"keyframes":[{"time":0,"properties":{"timestamp":500}},
                            {"time":250,"properties":{"timestamp":1000}},
                            {"time":1000,"properties":{"timestamp":1375}}],
               "segments":[0,0],"interpolators":[{"type":"linear","properties":["timestamp"]}]},
              {"keyframes":[{"time":0,"properties":{"camera:position":[0,0,0],"camera:rotation":[350,0,0]}},
                            {"time":1000,"properties":{"camera:position":[10,0,0],"camera:rotation":[10,0,0]}}],
               "segments":[0],"interpolators":[{"type":{"type":"catmull-rom-spline","alpha":0.5},
                                                 "properties":["camera:position","camera:rotation"]}]}
            ]
            """).getAsJsonArray();
    }

    @Test void aClockChangeSplitsTheCurveWithoutChangingItsShape() {
        var path = new ReplayModPath(shot(), 40, 85);
        assertEquals(10, path.start);
        assertEquals(28, path.end);
        assertEquals(3, path.keys.size());
        assertEquals(20, path.keys.get(1).tick);
        assertEquals(27.5, path.keys.getLast().tick);
        var clock = new FrameSchedule(path.speeds, path.start, path.end, 100);
        for (int frame = 0; frame <= 100; frame++) {
            double t = frame / 100.0;
            double tick = clock.tickAt(frame);
            assertEquals(frame <= 25 ? 10 + 40 * t : 20 + 10 * (t - 0.25), tick, 1e-10);
            double x = 5 * t + 15 * t * t - 10 * t * t * t;
            var pose = PathInterpolator.evaluate(path.keys, tick);
            assertEquals(x, pose.x(), 1e-10);
            assertEquals(350 + 2 * x, pose.yaw(), 0.00005);
            assertEquals(85, pose.fov());
        }
    }

    @Test void aFreezeCannotSilentlyCollapseCameraMovementIntoOneReplayTick() {
        var tracks = shot();
        tracks.get(0).getAsJsonObject().getAsJsonArray("keyframes").get(1)
            .getAsJsonObject().getAsJsonObject("properties").addProperty("timestamp", 500);
        assertThrows(IllegalArgumentException.class, () -> new ReplayModPath(tracks, 40, 70));
    }
}
