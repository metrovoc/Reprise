package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.editor.EnvironmentSettings.Clock;
import com.metrovoc.reprise.editor.EnvironmentSettings.Weather;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.EnumMap;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class EnvironmentScreen extends Screen {
    private final EditorScreen parent;
    private final ReplaySession session;
    private final EnumMap<Weather, Button> weatherButtons = new EnumMap<>(Weather.class);
    private EditBox time;
    private Button recorded, fixed, keys, addKey, removeKey, previous, next, curve;

    EnvironmentScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.environment.title"));
        this.parent = parent;
        this.session = session;
        session.setPlaying(false);
    }

    private EnvironmentSettings settings() {
        return session.project().environment;
    }

    @Override
    protected void init() {
        int x = width / 2 - 138, y = height / 2 - 112;
        Component timeLabel = Component.translatable("reprise.environment.time");
        time = addRenderableWidget(new EditBox(font, x, y + 35, 90, 20, timeLabel));
        time.setMaxLength(11);
        time.setFilter(s -> s.matches("-?[0-9]{0,10}"));
        readClock();

        fixed = button("fixed", x + 93, y + 35, 90, () -> edit(() -> {
            settings().fixedTime = value();
            settings().clock = Clock.FIXED;
        }));
        addKey = button("add_key", x + 186, y + 35, 90, () -> edit(() -> settings().key(cursor(), value())));
        recorded = button("recorded_time", x, y + 58, 90, () -> edit(() -> settings().clock = Clock.RECORDED));
        keys = button("use_keys", x + 93, y + 58, 90, () -> edit(() -> settings().clock = Clock.KEYFRAMED));
        removeKey = button("remove_key", x + 186, y + 58, 90, () -> edit(() -> {
            settings().keys.removeIf(k -> k.tick() == cursor());
            if (settings().keys.isEmpty() && settings().clock == Clock.KEYFRAMED) {
                settings().clock = Clock.RECORDED;
            }
        }));

        previous = button("previous", x, y + 81, 90, () -> jump(-1));
        next = button("next", x + 186, y + 81, 90, () -> jump(1));
        curve = button("curve.forward", x, y + 104, 276, () -> edit(() -> {
            var key = settings().keyAt(cursor());
            var curves = EnvironmentSettings.TimeCurve.values();
            settings().key(key.tick(), key.time(), curves[(key.curve().ordinal() + 1) % curves.length]);
        }));
        for (Weather weather : Weather.values()) {
            int i = weather.ordinal();
            weatherButtons.put(weather, button("weather." + weather.name().toLowerCase(Locale.ROOT),
                x + i % 3 * 93, y + 145 + i / 3 * 23, 90, () -> edit(() -> settings().weather = weather)));
        }
        addRenderableWidget(Button.builder(Component.translatable("reprise.environment.layers"), b ->
            minecraft.setScreen(new SceneLayersScreen(this, session))).bounds(x, y + 196, 136, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(x + 140, y + 196, 136, 20).build());
        time.setResponder(s -> update());
        update();
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable("reprise.environment." + key), b -> action.run())
            .bounds(x, y, width, 20).build());
    }

    private double cursor() {
        return session.project().worldTick(session.visualTick(1));
    }

    private void readClock() {
        var key = settings().keyAt(cursor());
        if (key != null && settings().clock == Clock.KEYFRAMED) {
            time.setValue(Integer.toString(key.time()));
            return;
        }
        time.setValue(String.valueOf(Math.floorMod(minecraft.level.getOverworldClockTime(), EnvironmentSettings.DAY)));
    }

    private Integer value() {
        try {
            return Integer.valueOf(time.getValue());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void edit(Runnable action) {
        session.project().checkpoint();
        action.run();
        session.project().save();
        session.refreshEnvironment();
        readClock();
        update();
    }

    private void jump(int direction) {
        double tick = cursor();
        var candidates = settings().keys.stream().mapToDouble(EnvironmentSettings.TimeKey::tick);
        double target = direction < 0 ? candidates.filter(t -> t < tick).max().orElse(tick)
            : candidates.filter(t -> t > tick).min().orElse(tick);
        session.seekEditorCursor(session.project().shotTick(target));
        readClock();
        update();
    }

    private void update() {
        Integer value = value();
        double tick = cursor();
        boolean valid = value != null && value >= 0 && value < EnvironmentSettings.DAY;
        fixed.active = valid && (settings().clock != Clock.FIXED || value != settings().fixedTime);
        recorded.active = settings().clock != Clock.RECORDED;
        keys.active = !settings().keys.isEmpty() && settings().clock != Clock.KEYFRAMED;
        var key = settings().keyAt(tick);
        addKey.active = value != null && (settings().clock != Clock.KEYFRAMED || key == null || key.time() != value);
        removeKey.active = key != null;
        curve.active = key != null;
        curve.setMessage(Component.translatable("reprise.environment.curve." +
            (key == null ? "forward" : key.curve().name().toLowerCase(Locale.ROOT))));
        previous.active = settings().keys.stream().anyMatch(k -> k.tick() < tick);
        next.active = settings().keys.stream().anyMatch(k -> k.tick() > tick);
        weatherButtons.forEach((weather, button) -> button.active = weather != settings().weather);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = width / 2 - 138, y = height / 2 - 112;
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(font, title, width / 2, y + 4, 0xFFFFFFFF);
        g.text(font, Component.translatable("reprise.environment.time"), x, y + 22, 0xFFFFFFFF);
        double tick = cursor();
        String position = tick == Math.rint(tick) ? Long.toString((long) tick) : Double.toString(tick);
        Component tickLabel = Component.translatable("reprise.environment.key_tick", position);
        g.centeredText(font, font.width(tickLabel) <= 90 ? tickLabel : Component.literal(position), width / 2, y + 87, 0xFFFFFFFF);
        g.text(font, Component.translatable("reprise.environment.weather"), x, y + 132, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
