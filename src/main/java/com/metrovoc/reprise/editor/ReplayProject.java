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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What a user builds on top of a replay: the camera path and export settings. Saved per replay. */
public final class ReplayProject {
    public record EntityOverride(String name, boolean hidden, Integer outlineColor, String label) {}

    public EnvironmentSettings environment = new EnvironmentSettings();
    public boolean hideNames;
    public Map<UUID, EntityOverride> entityOverrides = new HashMap<>();

    public void setEntityOverride(UUID id, String name, boolean hidden, Integer outlineColor, String label) {
        checkpoint();
        if (!hidden && outlineColor == null && label == null) {
            this.entityOverrides.remove(id);
        } else {
            this.entityOverrides.put(id, new EntityOverride(name, hidden, outlineColor, label));
        }
        save();
    }

    public List<Keyframe> keyframes = new ArrayList<>();
    public List<SpeedKey> speedKeys = new ArrayList<>();
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
        if (project.speedKeys == null) {
            project.speedKeys = new ArrayList<>();
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
        this.environment = state.environment;
        this.hideNames = state.hideNames;
        this.entityOverrides = state.entityOverrides;
        this.keyframes = state.keyframes;
        this.speedKeys = state.speedKeys == null ? new ArrayList<>() : state.speedKeys;
        this.exportStart = state.exportStart;
        this.exportEnd = state.exportEnd;
        this.export = state.export;
        save();
        return true;
    }

    public void sort() {
        this.keyframes.sort(Comparator.comparingDouble(k -> k.tick));
        this.speedKeys.sort(Comparator.comparingDouble(k -> k.tick));
    }

    /** The speed curve at a replay tick; 1 when there are no speed keys. */
    public double speedAt(double tick) {
        List<SpeedKey> keys = this.speedKeys;
        if (keys.isEmpty()) {
            return 1;
        }
        if (tick <= keys.get(0).tick) {
            return keys.get(0).speed;
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            SpeedKey a = keys.get(i), b = keys.get(i + 1);
            if (tick < b.tick) {
                double f = (tick - a.tick) / Math.max(1e-9, b.tick - a.tick);
                return a.speed + (b.speed - a.speed) * f;
            }
        }
        return keys.get(keys.size() - 1).speed;
    }

    /**
     * The replay tick of every video frame from {@code start} to {@code end}, following the speed curve.
     * Integrates with the midpoint rule, which keeps ramps accurate to a fraction of a tick.
     */
    public double[] frameTicks(int start, int end, int fps) {
        double step = 20.0 / fps;
        List<Double> out = new ArrayList<>();
        double t = start;
        while (t <= end + 1e-9 && out.size() < 10_000_000) {
            out.add(t);
            double mid = t + 0.5 * step * speedAt(t);
            t += step * Math.max(0.001, speedAt(mid));
        }
        double[] ticks = new double[out.size()];
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = out.get(i);
        }
        return ticks;
    }

    public int start() {
        return Math.max(0, this.exportStart);
    }

    public int end(int duration) {
        return this.exportEnd < 0 ? duration : Math.min(duration, this.exportEnd);
    }
}
