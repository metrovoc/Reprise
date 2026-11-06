package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.ReplayMeta;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** What a user builds on top of a replay: the camera path and export settings. Saved per replay. */
public final class ReplayProject {
    public List<Keyframe> keyframes = new ArrayList<>();
    /** The part of the replay to export, in ticks; -1 for the whole replay. */
    public int exportStart = -1;
    public int exportEnd = -1;
    public ExportSettings export = new ExportSettings();

    private transient Path path;
    private transient final List<String> undo = new ArrayList<>();
    private transient final List<String> redo = new ArrayList<>();

    public static ReplayProject load(ReplayMeta meta) {
        Path path = ReprisePaths.projects().resolve(meta.uuid + ".json");
        ReplayProject project = null;
        if (Files.exists(path)) {
            try {
                project = ReplayMeta.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), ReplayProject.class);
            } catch (Exception e) {
                Reprise.LOGGER.warn("Could not read {}", path, e);
            }
        }
        if (project == null) {
            project = new ReplayProject();
        }
        if (project.export == null) {
            project.export = new ExportSettings();
        }
        project.path = path;
        project.sort();
        return project;
    }

    public void save() {
        try {
            Files.writeString(this.path, ReplayMeta.GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not save {}", this.path, e);
        }
    }

    /** Call before every change, so that it can be undone. */
    public void checkpoint() {
        this.undo.add(ReplayMeta.GSON.toJson(this));
        if (this.undo.size() > 200) {
            this.undo.remove(0);
        }
        this.redo.clear();
    }

    public boolean undo() {
        return restore(this.undo, this.redo);
    }

    public boolean redo() {
        return restore(this.redo, this.undo);
    }

    private boolean restore(List<String> from, List<String> to) {
        if (from.isEmpty()) {
            return false;
        }
        to.add(ReplayMeta.GSON.toJson(this));
        ReplayProject state = ReplayMeta.GSON.fromJson(from.remove(from.size() - 1), ReplayProject.class);
        this.keyframes = state.keyframes;
        this.exportStart = state.exportStart;
        this.exportEnd = state.exportEnd;
        this.export = state.export;
        save();
        return true;
    }

    public void sort() {
        this.keyframes.sort(Comparator.comparingDouble(k -> k.tick));
    }

    public int start() {
        return Math.max(0, this.exportStart);
    }

    public int end(int duration) {
        return this.exportEnd < 0 ? duration : Math.min(duration, this.exportEnd);
    }
}
