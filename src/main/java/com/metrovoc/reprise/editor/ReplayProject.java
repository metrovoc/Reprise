package com.metrovoc.reprise.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.ReplayMeta;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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

    /** A shot interval in which the camera clock advances while the recording stays at worldTick. */
    public record Freeze(double worldTick, int length) {}
    public List<Freeze> freezes = new ArrayList<>();

    public int shotDuration(int recordedTicks) {
        return Math.addExact(recordedTicks, addedTicks());
    }

    public int addedTicks() {
        int extra = 0;
        for (var freeze : freezes) extra = Math.addExact(extra, freeze.length);
        return extra;
    }

    public double worldTick(double shotTick) {
        int extra = 0;
        for (var freeze : freezes) {
            double start = freeze.worldTick + extra;
            if (shotTick < start) break;
            if (shotTick <= start + freeze.length) return freeze.worldTick;
            extra += freeze.length;
        }
        return shotTick - extra;
    }

    public double shotTick(double worldTick) {
        double shot = worldTick;
        for (var freeze : freezes) {
            if (worldTick <= freeze.worldTick) break;
            shot += freeze.length;
        }
        return shot;
    }

    public List<Keyframe> keyframes = new ArrayList<>();
    public boolean shakeEnabled = true;
    public List<CameraShake.Key> shakeKeys = new ArrayList<>();
    private transient CameraShake shake;
    public List<SpeedKey> speedKeys = new ArrayList<>();
    /** The part of the replay to export, in ticks; -1 for the whole replay. */
    public int exportStart = -1;
    public int exportEnd = -1;
    public ExportSettings export = new ExportSettings();

    public com.metrovoc.reprise.gui.Timeline.View timelineView;
    public transient final List<QueuedShot> renderQueue = new ArrayList<>();
    private transient Path path;
    private transient FrameSchedule previewClock;
    private transient int previewDuration;
    private transient ProjectHistory history = new ProjectHistory();
    private transient JsonObject beforeEdit;

    public static ReplayProject load(ReplayMeta meta) {
        return load(ReprisePaths.projects(), meta.uuid);
    }

    static ReplayProject load(Path directory, String id) {
        Path filename = Path.of(id + ".json");
        if (filename.isAbsolute() || filename.getNameCount() != 1) {
            throw new IllegalArgumentException("Invalid replay project identifier");
        }
        Path path = directory.resolve(filename);
        ReplayProject project = new ReplayProject();
        if (Files.exists(path)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                project = ReplayMeta.GSON.fromJson(saved, ReplayProject.class);
                if (saved.has("renderQueue")) {
                    project.renderQueue.addAll(List.of(ReplayMeta.GSON.fromJson(saved.get("renderQueue"), QueuedShot[].class)));
                }
                if (saved.has("history")) {
                    project.history = ReplayMeta.GSON.fromJson(saved.get("history"), ProjectHistory.class);
                }
            } catch (RuntimeException | IOException e) {
                // Opening an unreadable project must never overwrite the user's camera work on exit.
                throw new IllegalStateException("Could not open replay project " + filename, e);
            }
        }
        if (project.speedKeys == null) project.speedKeys = new ArrayList<>();
        if (project.export == null) project.export = new ExportSettings();
        project.path = path;
        project.sort();
        return project;
    }

    private JsonObject state() {
        JsonObject state = ReplayMeta.GSON.toJsonTree(this).getAsJsonObject();
        state.remove("timelineView");
        return state;
    }

    public void queueShot(String name, CameraPose camera, UUID spectating) {
        ReplayProject snapshot = ReplayMeta.GSON.fromJson(state(), ReplayProject.class);
        renderQueue.add(new QueuedShot(name.strip(), snapshot, camera, spectating));
        save();
    }

    public void save() {
        previewClock = null;
        shake = null;
        JsonObject state = state();
        finishEdit(state);
        if (this.timelineView != null) state.add("timelineView", ReplayMeta.GSON.toJsonTree(this.timelineView));
        if (!this.renderQueue.isEmpty()) state.add("renderQueue", ReplayMeta.GSON.toJsonTree(this.renderQueue));
        state.add("history", ReplayMeta.GSON.toJsonTree(this.history));
        try {
            Path temp = Files.createTempFile(this.path.getParent(), ".project-", ".tmp");
            try {
                Files.writeString(temp, ReplayMeta.GSON.toJson(state), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, this.path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            Reprise.LOGGER.warn("Could not save {}", this.path, e);
        }
    }

    /** Begins one undoable edit, committed on save. */
    public void checkpoint() {
        JsonObject state = state();
        finishEdit(state);
        this.beforeEdit = state;
    }

    private void finishEdit(JsonObject state) {
        if (this.beforeEdit != null) {
            this.history.record(this.beforeEdit, state);
            this.beforeEdit = null;
        }
    }

    public boolean canUndo() {
        return this.history.available(false);
    }

    public boolean canRedo() {
        return this.history.available(true);
    }

    public boolean undo() {
        return restore(false);
    }

    public boolean redo() {
        return restore(true);
    }

    private boolean restore(boolean forward) {
        JsonObject current = state();
        finishEdit(current);
        if (!this.history.apply(current, forward)) return false;
        ReplayProject state = ReplayMeta.GSON.fromJson(current, ReplayProject.class);
        this.environment = state.environment;
        this.shakeKeys = state.shakeKeys;
        this.shakeEnabled = state.shakeEnabled;
        this.hideNames = state.hideNames;
        this.entityOverrides = state.entityOverrides;
        this.freezes = state.freezes;
        this.keyframes = state.keyframes;
        this.speedKeys = state.speedKeys == null ? new ArrayList<>() : state.speedKeys;
        this.exportStart = state.exportStart;
        this.exportEnd = state.exportEnd;
        this.export = state.export;
        save();
        return true;
    }

    public void insertFreeze(int shotTick, int length) {
        if (length <= 0) throw new IllegalArgumentException("A freeze needs a positive length");
        checkpoint();
        double world = worldTick(shotTick);
        var updated = new ArrayList<>(freezes);
        updated.add(new Freeze(world, length));
        updated.sort(Comparator.comparingDouble(Freeze::worldTick));
        freezes = updated;
        for (var key : keyframes) if (key.tick > shotTick) key.tick += length;
        for (var key : speedKeys) if (key.tick > shotTick) key.tick += length;
        shakeKeys.replaceAll(k -> k.tick() > shotTick ? k.at(k.tick() + length) : k);
        if (exportStart > shotTick) exportStart += length;
        if (exportEnd >= shotTick) exportEnd += length;
        save();
    }

    void pasteCameraKeys(java.util.List<Keyframe> keys) {
        checkpoint();
        insertCameraKeys(keys);
    }

    void retimeCameraKeys(Map<Keyframe, Double> times) {
        var keys = times.keySet();
        if (keys.isEmpty()) return;
        checkpoint();
        keyframes.removeIf(keys::contains);
        times.forEach((key, tick) -> key.tick = tick);
        insertCameraKeys(keys);
    }

    private void insertCameraKeys(java.util.Collection<Keyframe> keys) {
        var times = new java.util.HashSet<Double>();
        keys.forEach(key -> times.add(key.tick));
        keyframes.removeIf(key -> times.contains(key.tick));
        keyframes.addAll(keys);
        sort();
        save();
    }

    public void putCameraKey(Keyframe key) {
        this.keyframes.removeIf(existing -> existing == key || existing.tick == key.tick);
        this.keyframes.add(key);
        sort();
    }

    public void sort() {
        previewClock = null;
        shake = null;
        this.keyframes.sort(Comparator.comparingDouble(k -> k.tick));
        this.shakeKeys.sort(Comparator.comparingDouble(CameraShake.Key::tick));
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
                if (a.hold) return a.speed;
                double f = (tick - a.tick) / Math.max(1e-9, b.tick - a.tick);
                return a.speed + (b.speed - a.speed) * f;
            }
        }
        return keys.get(keys.size() - 1).speed;
    }

    public CameraShake shake(int duration) {
        FrameSchedule clock = previewClock(duration);
        if (shake == null) shake = new CameraShake(shakeKeys, clock);
        return shake;
    }

    private FrameSchedule previewClock(int duration) {
        if (previewClock == null || previewDuration != duration) {
            previewClock = frameSchedule(0, duration, 20);
            previewDuration = duration;
            shake = null;
        }
        return previewClock;
    }

    public float previewMillisPerTick(int tick, int duration) {
        if (tick >= duration) return (float) (50 / Math.max(0.001, speedAt(tick)));
        FrameSchedule clock = previewClock(duration);
        return (float) (50 * (clock.videoTimeAt(tick + 1.0) - clock.videoTimeAt(tick)));
    }

    public FrameSchedule frameSchedule(int start, int end, double fps) {
        return new FrameSchedule(this.speedKeys, start, end, fps);
    }

    /** Materializes the schedule for callers that need an array of frame times. */
    public double[] frameTicks(int start, int end, double fps) {
        var schedule = frameSchedule(start, end, fps);
        double[] ticks = new double[Math.toIntExact(schedule.frameCount())];
        for (int i = 0; i < ticks.length; i++) ticks[i] = schedule.tickAt(i);
        return ticks;
    }

    /** Strictly adjacent timeline cue in shot time, including the ends of freeze intervals. */
    double adjacentCue(double cursor, boolean forward, int duration, List<ReplayMeta.Marker> markers) {
        class Neighbor {
            double tick = forward ? duration : 0;
            void consider(double candidate) {
                if (forward ? candidate > cursor && candidate < tick : candidate < cursor && candidate > tick) tick = candidate;
            }
        }
        var neighbor = new Neighbor();
        neighbor.consider(start());
        neighbor.consider(end(duration));
        for (var key : keyframes) neighbor.consider(key.tick);
        for (var key : speedKeys) neighbor.consider(key.tick);
        for (var key : shakeKeys) neighbor.consider(key.tick());
        for (var key : environment.keys) neighbor.consider(shotTick(key.tick()));
        for (var marker : markers) neighbor.consider(shotTick(marker.tick));
        int extra = 0;
        for (var freeze : freezes) {
            neighbor.consider(freeze.worldTick() + extra);
            extra += freeze.length();
            neighbor.consider(freeze.worldTick() + extra);
        }
        return neighbor.tick;
    }

    public int start() {
        return Math.max(0, this.exportStart);
    }

    public int end(int duration) {
        return this.exportEnd < 0 ? duration : Math.min(duration, this.exportEnd);
    }
}
