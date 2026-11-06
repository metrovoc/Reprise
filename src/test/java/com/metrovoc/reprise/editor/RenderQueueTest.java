package com.metrovoc.reprise.editor;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RenderQueueTest {
    @Test void queuedCameraSceneRangeAndSettingsSurviveIndependentEditorChanges(@TempDir Path dir) {
        var project = ReplayProject.load(dir, "replay");
        var key = new Keyframe();
        key.tick = 20;
        key.roll = 45;
        project.keyframes.add(key);
        project.environment.nightVision = true;
        project.exportStart = 20;
        project.export.width = 1280;
        var camera = new CameraPose(1, 2, 3, 4, 5, 6, 80);
        UUID actor = UUID.randomUUID();
        project.queueShot("Shot A", camera, actor);
        project.checkpoint();
        key.roll = 0;
        project.environment.nightVision = false;
        project.exportStart = 50;
        project.export.width = 640;
        project.save();
        project.queueShot("Shot B", camera, null);
        assertTrue(project.undo());

        var reopened = ReplayProject.load(dir, "replay");
        assertEquals(2, reopened.renderQueue.size());
        var first = reopened.renderQueue.getFirst();
        var second = reopened.renderQueue.getLast();
        assertEquals("Shot A", first.name());
        assertEquals(actor, first.spectating());
        assertEquals(camera, first.camera());
        assertEquals(45, first.project().keyframes.getFirst().roll);
        assertTrue(first.project().environment.nightVision);
        assertEquals(20, first.project().exportStart);
        assertEquals(1280, first.project().export.width);
        assertNull(second.spectating());
        assertEquals(0, second.project().keyframes.getFirst().roll);
        assertEquals(50, second.project().exportStart);
        assertEquals(640, second.project().export.width);
        assertTrue(second.project().renderQueue.isEmpty());
    }
}
