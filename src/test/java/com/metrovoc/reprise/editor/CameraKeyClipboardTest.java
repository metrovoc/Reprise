package com.metrovoc.reprise.editor;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CameraKeyClipboardTest {
    @Test void pastePreservesOffsetsAndRestoresReplacedKeysOnUndo(@TempDir Path dir) {
        var project = ReplayProject.load(dir, "scene");
        var first = new Keyframe();
        first.tick = 3.125;
        first.turns = 2;
        first.target = new Keyframe.Target(null, "Anchor", Keyframe.Tracking.ORBIT, Keyframe.AimPoint.CENTER, 1, 2, 3);
        var next = new Keyframe();
        next.tick = 7.25;
        next.spectatorAfterKey = 42;
        next.spline = new Keyframe.Spline(new double[6], new double[]{1, 2, 3, 4, 5, 6});
        var occupied = new Keyframe();
        occupied.tick = 40;
        occupied.x = 100;
        project.putCameraKey(occupied);
        var payload = CameraKeyClipboard.encode(List.of(next, first));
        project.pasteCameraKeys(CameraKeyClipboard.decode(payload, 40, 100));
        assertEquals(List.of(40.0, 44.125), project.keyframes.stream().map(k -> k.tick).toList());
        assertEquals(first.target, project.keyframes.getFirst().target);
        assertEquals(2, project.keyframes.getFirst().turns);
        assertEquals(42, project.keyframes.getLast().spectatorAfterKey);
        assertArrayEquals(next.spline.endOffset(), project.keyframes.getLast().spline.endOffset());
        assertEquals(1, CameraKeyClipboard.decode(payload, 96, 100).size());
        assertTrue(project.undo());
        assertEquals(1, project.keyframes.size());
        assertEquals(100, project.keyframes.getFirst().x);
        assertTrue(project.redo());
        assertEquals(2, project.keyframes.size());
        assertTrue(CameraKeyClipboard.decode(payload.replace("\"fov\"", "\"other\""), 0, 100).isEmpty());
        assertTrue(CameraKeyClipboard.decode("unrelated clipboard", 0, 100).isEmpty());
    }
}
