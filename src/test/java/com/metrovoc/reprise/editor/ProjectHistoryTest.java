package com.metrovoc.reprise.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectHistoryTest {
    @TempDir Path dir;

    @Test void reopenUndoRedoAndBranchFromLegacyProject() throws Exception {
        Files.writeString(dir.resolve("project.json"), "{\"exportStart\":10}");
        ReplayProject project = ReplayProject.load(dir, "project");
        project.checkpoint();
        var key = new Keyframe();
        key.tick = 4;
        project.keyframes.add(key);
        project.save();
        project.checkpoint();
        project.keyframes.getFirst().x = 32;
        project.save();

        project = ReplayProject.load(dir, "project");
        assertTrue(project.undo());
        assertEquals(0, project.keyframes.getFirst().x);
        project.checkpoint(); // Selecting and releasing a key without moving it.
        project.save();
        project = ReplayProject.load(dir, "project");
        assertTrue(project.redo());
        assertEquals(32, project.keyframes.getFirst().x);
        assertTrue(project.undo());
        assertTrue(project.undo());
        assertTrue(project.keyframes.isEmpty());
        assertFalse(project.undo());
        project.checkpoint();
        project.exportStart = 12;
        project.save();
        project = ReplayProject.load(dir, "project");
        assertFalse(project.redo());
        assertTrue(project.undo());
        assertEquals(10, project.exportStart);
    }

    @Test void limitsHistoryAndStoresOnlyTheChangedCameraKey() throws Exception {
        ReplayProject project = ReplayProject.load(dir, "project");
        for (int i = 0; i < 1000; i++) {
            var key = new Keyframe();
            key.tick = i;
            project.keyframes.add(key);
        }
        project.save();
        long initialBytes = Files.size(dir.resolve("project.json"));
        project.checkpoint();
        project.keyframes.get(500).roll = 42;
        project.save();
        assertTrue(Files.size(dir.resolve("project.json")) - initialBytes < 2048);
        project = ReplayProject.load(dir, "project");
        assertTrue(project.undo());
        assertEquals(0, project.keyframes.get(500).roll);
        assertEquals(1000, project.keyframes.size());

        var small = ReplayProject.load(dir, "many-edits");
        for (int i = 1; i <= 205; i++) {
            small.checkpoint();
            small.exportStart = i;
            small.save();
        }
        small = ReplayProject.load(dir, "many-edits");
        for (int i = 0; i < 200; i++) assertTrue(small.undo());
        assertFalse(small.undo());
        assertEquals(5, small.exportStart);
    }

    @Test void unreadableProjectIsPreservedAndPathMustStayInProjectDirectory() throws Exception {
        Path file = dir.resolve("project.json");
        Files.writeString(file, "{unfinished");
        assertThrows(IllegalStateException.class, () -> ReplayProject.load(dir, "project"));
        assertEquals("{unfinished", Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> ReplayProject.load(dir, "../outside"));
    }
}
