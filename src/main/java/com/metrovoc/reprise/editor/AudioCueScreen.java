package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.export.SoundtrackSource;
import com.metrovoc.reprise.playback.ReplaySession;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

final class AudioCueScreen extends Screen {
    private final EditorScreen parent;
    private final ReplaySession session;
    private final double cursor;
    private final AudioCue original;
    private EditBox tick, offset, length, gain;
    private Button save;
    private String file;
    private CompletableFuture<SoundtrackSource> loading;
    private Component status;

    AudioCueScreen(EditorScreen parent, ReplaySession session) {
        super(label("title"));
        this.parent = parent;
        this.session = session;
        cursor = session.visualTick(1);
        session.seekEditorCursor(cursor);
        original = session.project().audioCues.stream().filter(c -> c.tick() == cursor).findFirst().orElse(null);
        if (original != null) select(Path.of(original.file()));
    }

    private static Component label(String key) { return Component.translatable("reprise.audio." + key); }
    private void select(Path path) {
        session.soundtrack.discardUnused(session);
        file = path.toAbsolutePath().normalize().toString();
        var request = session.soundtrack.load(file, session.project().export.ffmpeg, true);
        loading = request;
        request.whenComplete((source, error) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen == this && loading == request && save != null) validateCue();
        }));
        if (save != null) validateCue();
    }

    @Override protected void init() {
        int x = width / 2 - 138, y = height / 2 - 100;
        button("choose", x, y + 24, 276, () -> {
            String picked = TinyFileDialogs.tinyfd_openFileDialog(label("choose").getString(), "", null, null, false);
            if (picked != null) select(Path.of(picked));
        });
        tick = field(tick, x, y + 81, "tick", cursor);
        offset = field(offset, x + 140, y + 81, "offset", original == null ? 0 : original.offset());
        length = field(length, x, y + 116, "length", original == null ? 0 : original.length());
        gain = field(gain, x + 140, y + 116, "gain", original == null ? 100 : original.gain() * 100);
        button("previous", x, y + 142, 136, () -> jump(false)).active = neighbor(false) != null;
        button("next", x + 140, y + 142, 136, () -> jump(true)).active = neighbor(true) != null;
        save = button("save", x, y + 168, 93, this::apply);
        button("remove", x + 97, y + 168, 88, () -> {
            session.project().checkpoint();
            session.project().audioCues.remove(original);
            session.project().save();
            onClose();
        }).active = original != null;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 189, y + 168, 87, 20).build());
        for (var field : List.of(tick, offset, length, gain)) field.setResponder(text -> validateCue());
        validateCue();
    }

    private EditBox field(EditBox old, int x, int y, String name, double value) {
        var field = addRenderableWidget(new EditBox(font, x, y, 136, 20, label(name)));
        field.setValue(old == null ? Double.toString(value) : old.getValue());
        return field;
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(label(key), b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override public void onFilesDrop(List<Path> paths) {
        if (paths.size() == 1) select(paths.getFirst());
    }

    private AudioCue neighbor(boolean next) {
        var order = Comparator.comparingDouble(AudioCue::tick);
        return session.project().audioCues.stream().filter(c -> next ? c.tick() > cursor : c.tick() < cursor)
            .min(next ? order : order.reversed()).orElse(null);
    }

    private void jump(boolean next) {
        var cue = neighbor(next);
        if (cue != null) {
            session.seekEditorCursor(cue.tick());
            minecraft.setScreen(new AudioCueScreen(parent, session));
        }
    }

    private AudioCue validateCue() {
        save.active = false;
        status = null;
        if (loading == null) { status = label("drop"); return null; }
        try {
            var source = loading.getNow(null);
            if (source == null) { status = label("loading"); return null; }
            status = Component.literal(Path.of(file).getFileName() + String.format(Locale.ROOT, "  ·  %.3f s", source.frames() / 48000.0));
            double at = Double.parseDouble(tick.getValue()), start = Double.parseDouble(offset.getValue());
            double len = Double.parseDouble(length.getValue());
            double level = Double.parseDouble(gain.getValue());
            if (!Double.isFinite(at) || at < 0 || at > session.duration() || !Double.isFinite(start) || start < 0
                || !Double.isFinite(len) || len < 0 || (len > 0 && Math.round(len * 48000) == 0)
                || Math.round(start * 48000) >= source.frames() || !Double.isFinite(level) || level < 0 || level > 200) {
                status = label("range"); return null;
            }
            if (session.project().audioCues.stream().anyMatch(c -> c != original && c.tick() == at)) {
                status = label("occupied"); return null;
            }
            save.active = true;
            return new AudioCue(at, file, start, len, level / 100, source.frames());
        } catch (CompletionException e) {
            status = Component.literal(e.getCause().getMessage());
        } catch (NumberFormatException e) {
            status = label("range");
        }
        return null;
    }

    private void apply() {
        var cue = validateCue();
        if (cue == null) return;
        var project = session.project();
        project.checkpoint();
        if (project.audioCues.isEmpty()) project.export.audio = true;
        project.audioCues.remove(original);
        project.audioCues.add(cue);
        project.audioCues.sort(Comparator.comparingDouble(AudioCue::tick));
        project.save();
        session.seekEditorCursor(cue.tick());
        onClose();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        int y = height / 2 - 100;
        g.centeredText(font, title, width / 2, y + 6, -1);
        if (status != null) g.centeredText(font, font.plainSubstrByWidth(status.getString(), 276), width / 2, y + 51, -1);
        for (var f : List.of(tick, offset, length, gain)) g.text(font, f.getMessage(), f.getX(), f.getY() - 11, -1);
    }

    @Override public void removed() {
        session.soundtrack.discardUnused(session);
        super.removed();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
