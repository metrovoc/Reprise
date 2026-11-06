package com.metrovoc.reprise.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayModPathTest {
    private static final CameraPose INITIAL = new CameraPose(42, 104, -7, 15, 5, 2, 85);

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
        var path = new ReplayModPath(shot(), 40, INITIAL);
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

    @Test void shorterCameraTrackHoldsTheIncomingAndFinalPoses() {
        var tracks = shot();
        var positions = tracks.get(1).getAsJsonObject().getAsJsonArray("keyframes");
        positions.get(0).getAsJsonObject().addProperty("time", 400);
        positions.get(1).getAsJsonObject().addProperty("time", 800);
        var path = new ReplayModPath(tracks, 40, INITIAL);
        assertEquals(3, path.keys.size());
        assertEquals(10, path.start);
        assertEquals(28, path.end);
        assertEquals(INITIAL, PathInterpolator.evaluate(path.keys, 21.49));
        assertEquals(0, PathInterpolator.evaluate(path.keys, 21.5).x());
        assertEquals(5, PathInterpolator.evaluate(path.keys, 23.5).x(), 1e-10);
        assertEquals(10, PathInterpolator.evaluate(path.keys, 25.5).x());
        assertEquals(10, PathInterpolator.evaluate(path.keys, 27.5).x());
    }

    @Test void cameraAdvancesWhileTheRecordingStaysAtTheFreezeTick() {
        var tracks = shot();
        tracks.get(0).getAsJsonObject().getAsJsonArray("keyframes").get(1)
            .getAsJsonObject().getAsJsonObject("properties").addProperty("timestamp", 500);
        var path = new ReplayModPath(tracks, 40, INITIAL);
        var project = new ReplayProject();
        project.freezes = path.freezes;
        var clock = new FrameSchedule(path.speeds, path.start, path.end, 100);
        for (int frame = 0; frame <= 25; frame++) {
            double shot = clock.tickAt(frame);
            assertEquals(10, project.worldTick(shot), 1e-10);
            assertEquals(10 + frame / 5.0, shot, 1e-10);
        }
        assertTrue(PathInterpolator.evaluate(path.keys, clock.tickAt(25)).x() > 0);
        assertEquals(27.5, project.worldTick(clock.tickAt(100)), 1e-10);
    }
    @Test void adjacentFractionalHoldsAtTheEndKeepTheirVideoLengths() {
        var tracks = shot();
        var times = tracks.get(0).getAsJsonObject().getAsJsonArray("keyframes");
        for (var value : times) value.getAsJsonObject().getAsJsonObject("properties").addProperty("timestamp", 2000);
        times.get(1).getAsJsonObject().addProperty("time", 267);
        var path = new ReplayModPath(tracks, 40, INITIAL);
        var project = new ReplayProject();
        project.freezes = path.freezes;
        var schedule = new FrameSchedule(path.speeds, path.start, path.end, 1000);
        assertEquals(1001, schedule.frameCount());
        assertEquals(21, project.addedTicks());
        for (int frame = 0; frame <= 1000; frame++) {
            assertEquals(40, project.worldTick(schedule.tickAt(frame)), 1e-12);
        }
        assertEquals(10, PathInterpolator.evaluate(path.keys, schedule.tickAt(1000)).x(), 1e-10);
    }

}
