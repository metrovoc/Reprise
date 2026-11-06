package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.editor.EnvironmentSettings.Clock;
import com.metrovoc.reprise.editor.EnvironmentSettings.Weather;
import com.metrovoc.reprise.gui.Timecode;
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
    private Button recorded, fixed, keys, addKey, removeKey, previous, next;

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
        int x = width / 2 - 138, y = height / 2 - 103;
        Component timeLabel = Component.translatable("reprise.environment.time");
        time = addRenderableWidget(new EditBox(font, x, y + 35, 90, 20, timeLabel));
        time.setMaxLength(5);
        time.setFilter(s -> s.matches("[0-9]{0,5}"));
        readClock();

        fixed = button("fixed", x + 93, y + 35, 90, () -> edit(() -> {
            settings().fixedTime = value();
            settings().clock = Clock.FIXED;
        }));
        addKey = button("add_key", x + 186, y + 35, 90, () -> edit(() -> settings().key(session.tick(), value())));
        recorded = button("recorded_time", x, y + 58, 90, () -> edit(() -> settings().clock = Clock.RECORDED));
        keys = button("use_keys", x + 93, y + 58, 90, () -> edit(() -> settings().clock = Clock.KEYFRAMED));
        removeKey = button("remove_key", x + 186, y + 58, 90, () -> edit(() -> {
            settings().keys.removeIf(k -> k.tick() == session.tick());
            if (settings().keys.isEmpty() && settings().clock == Clock.KEYFRAMED) {
                settings().clock = Clock.RECORDED;
            }
        }));

        previous = button("previous", x, y + 81, 90, () -> jump(-1));
        next = button("next", x + 186, y + 81, 90, () -> jump(1));
        for (Weather weather : Weather.values()) {
            int i = weather.ordinal();
            weatherButtons.put(weather, button("weather." + weather.name().toLowerCase(Locale.ROOT),
                x + i % 3 * 93, y + 119 + i / 3 * 23, 90, () -> edit(() -> settings().weather = weather)));
        }
        addRenderableWidget(Button.builder(Component.translatable("reprise.environment.layers"), b ->
            minecraft.setScreen(new SceneLayersScreen(this, session))).bounds(x, y + 171, 136, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(x + 140, y + 171, 136, 20).build());
        time.setResponder(s -> update());
        update();
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable("reprise.environment." + key), b -> action.run())
            .bounds(x, y, width, 20).build());
    }

    private void readClock() {
        time.setValue(String.valueOf(Math.floorMod(minecraft.level.getOverworldClockTime(), EnvironmentSettings.DAY)));
    }

    private int value() {
        return time.getValue().isEmpty() ? -1 : Integer.parseInt(time.getValue());
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
        int tick = session.tick();
        var candidates = settings().keys.stream().mapToInt(EnvironmentSettings.TimeKey::tick);
        int target = direction < 0 ? candidates.filter(t -> t < tick).max().orElse(tick)
            : candidates.filter(t -> t > tick).min().orElse(tick);
        session.seek(target);
        readClock();
        update();
    }

    private void update() {
        int value = value(), tick = session.tick();
        boolean valid = value >= 0 && value < EnvironmentSettings.DAY;
        fixed.active = valid && (settings().clock != Clock.FIXED || value != settings().fixedTime);
        recorded.active = settings().clock != Clock.RECORDED;
        keys.active = !settings().keys.isEmpty() && settings().clock != Clock.KEYFRAMED;
        addKey.active = valid && (settings().clock != Clock.KEYFRAMED
            || settings().keys.stream().noneMatch(k -> k.tick() == tick && k.time() == value));
        removeKey.active = settings().keys.stream().anyMatch(k -> k.tick() == tick);
        previous.active = settings().keys.stream().anyMatch(k -> k.tick() < tick);
        next.active = settings().keys.stream().anyMatch(k -> k.tick() > tick);
        weatherButtons.forEach((weather, button) -> button.active = weather != settings().weather);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = width / 2 - 138, y = height / 2 - 103;
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(font, title, width / 2, y + 4, 0xFFFFFFFF);
        g.text(font, Component.translatable("reprise.environment.time"), x, y + 22, 0xFFFFFFFF);
        g.centeredText(font, Timecode.precise(session.tick()), width / 2, y + 87, 0xFFFFFFFF);
        g.text(font, Component.translatable("reprise.environment.weather"), x, y + 107, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
