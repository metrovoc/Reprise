package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.format.ReplayMeta;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineNavigationTest {
    @Test void visitsStrictNeighborsOnTheShotClockInBothDirections() {
        var project = new ReplayProject();
        project.freezes.add(new ReplayProject.Freeze(5, 10));
        project.exportStart = 2;
        project.exportEnd = 24;
        var camera = new Keyframe();
        camera.tick = 15.125;
        project.putCameraKey(camera);
        project.speedKeys.add(new SpeedKey(15.125, 1));
        project.environment.keys.add(new EnvironmentSettings.TimeKey(7, 100));
        var marker = new ReplayMeta.Marker();
        marker.tick = 6;
        var markers = List.of(marker);
        double[] cues = {0, 2, 5, 15, 15.125, 16, 17, 24, 30};
        for (int i = 0; i < cues.length; i++) {
            assertEquals(cues[Math.max(0, i - 1)], project.adjacentCue(cues[i], false, 30, markers));
            assertEquals(cues[Math.min(i + 1, cues.length - 1)], project.adjacentCue(cues[i], true, 30, markers));
        }
    }
}
