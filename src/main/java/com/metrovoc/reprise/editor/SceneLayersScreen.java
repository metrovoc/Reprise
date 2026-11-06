package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.editor.EnvironmentSettings.Fog;
import com.metrovoc.reprise.editor.EnvironmentSettings.Layer;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.EnumMap;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class SceneLayersScreen extends Screen {
    private final Screen parent;
    private final ReplaySession session;
    private final EnumMap<Layer, Button> buttons = new EnumMap<>(Layer.class);
    private Button vision, applySky, resetSky, applyFog, resetFog;
    private EditBox sky, near, far, tint;
    private boolean shaders;

    SceneLayersScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.environment.layers"));
        this.parent = parent;
        this.session = session;
    }

    private EnvironmentSettings settings() {
        return session.project().environment;
    }

    @Override
    protected void init() {
        shaders = com.metrovoc.reprise.export.ShaderPacks.active();
        int x = width / 2 - 138, y = height / 2 - 107;
        for (Layer layer : Layer.values()) {
            int i = layer.ordinal();
            buttons.put(layer, button("", x + (i % 3) * 93, y + 20 + (i / 3) * 23, 90, () -> {
                if (!settings().hidden.remove(layer)) {
                    settings().hidden.add(layer);
                }
            }));
        }
        vision = button("", x + 186, y + 43, 90, () -> settings().nightVision = !settings().nightVision);

        sky = field("sky_color", x, y + 81, 90, hex(settings().skyColor == null ? 0x00FF00 : settings().skyColor));
        applySky = button("solid_sky", x + 93, y + 81, 90, () -> settings().skyColor = parseColor(sky));
        resetSky = button("normal_sky", x + 186, y + 81, 90, () -> settings().skyColor = null);
        Fog fog = settings().fog;
        near = field("fog_start", x, y + 120, 90, fog == null ? "0" : String.valueOf(fog.start()));
        far = field("fog_end", x + 93, y + 120, 90, fog == null ? "64" : String.valueOf(fog.end()));
        tint = field("fog_color", x + 186, y + 120, 90, fog == null || fog.color() == null ? "" : hex(fog.color()));
        tint.setHint(Component.translatable("reprise.environment.auto_color"));
        applyFog = button("apply_fog", x, y + 144, 136, () -> settings().fog = new Fog(distance(near), distance(far), parseColor(tint)));
        resetFog = button("normal_fog", x + 140, y + 144, 136, () -> settings().fog = null);
        for (EditBox field : new EditBox[]{sky, near, far, tint}) {
            field.setResponder(s -> update());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 65, y + 174, 130, 20).build());
        update();
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        Component text = key.isEmpty() ? Component.empty() : Component.translatable("reprise.environment." + key);
        return addRenderableWidget(Button.builder(text, b -> {
            session.project().checkpoint();
            action.run();
            session.project().save();
            session.refreshEnvironment();
            update();
        }).bounds(x, y, w, 20).build());
    }

    private EditBox field(String key, int x, int y, int width, String value) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.translatable("reprise.environment." + key)));
        box.setValue(value);
        return box;
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb);
    }

    private static Integer parseColor(EditBox box) {
        String value = box.getValue();
        return value.matches("[0-9A-Fa-f]{6}") ? Integer.valueOf(value, 16) : null;
    }

    private static float distance(EditBox box) {
        try {
            return Float.parseFloat(box.getValue());
        } catch (NumberFormatException ignored) {
            return Float.NaN;
        }
    }

    private static Component toggle(String name, boolean enabled) {
        return Component.translatable("reprise.environment.toggle", Component.translatable("reprise.environment." + name),
            Component.translatable(enabled ? "options.on" : "options.off"));
    }

    private void update() {
        buttons.forEach((layer, button) -> button.setMessage(toggle(layer.name().toLowerCase(Locale.ROOT), !settings().hidden.contains(layer))));
        vision.setMessage(toggle("night_vision", settings().nightVision));
        Integer color = parseColor(sky);
        applySky.active = color != null && !color.equals(settings().skyColor);
        resetSky.active = settings().skyColor != null;
        float start = distance(near), end = distance(far);
        Integer fogColor = parseColor(tint);
        applyFog.active = start >= 0 && Float.isFinite(end) && end > start && (tint.getValue().isBlank() || fogColor != null)
            && !new Fog(start, end, fogColor).equals(settings().fog);
        resetFog.active = settings().fog != null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(font, title, width / 2, height / 2 - 106, 0xFFFFFFFF);
        if (shaders) {
            g.text(font, Component.translatable("reprise.environment.shader_sky"), sky.getX() + 93, sky.getY() - 12, 0xFFFFD080);
        }
        for (EditBox box : new EditBox[]{sky, near, far, tint}) {
            g.text(font, box.getMessage(), box.getX(), box.getY() - 12, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
