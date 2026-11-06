package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.editor.ReplayProject.EntityOverride;
import com.metrovoc.reprise.playback.ReplaySession;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

final class EntityScreen extends Screen {
    private final Screen parent;
    private final ReplaySession session;
    private EntityList list;
    private Button visibility;
    private Button outline;
    private Button spectate;
    private EditBox color;
    private EditBox nameTag;
    private Button applyName;
    private Entry editing;

    EntityScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.entities.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        this.list = addRenderableWidget(new EntityList(this.minecraft));
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Entity entity : this.minecraft.level.entitiesForRendering()) {
            if (entity != this.minecraft.player) {
                names.put(entity.getUUID(), entity.getName().getString());
            }
        }
        this.session.project().entityOverrides.forEach((id, override) -> names.putIfAbsent(id, override.name()));
        int searchWidth = Math.min(this.width - 18, 240);
        Component searchLabel = Component.translatable("reprise.entities.search");
        EditBox search = addRenderableWidget(new EditBox(this.font, (this.width - searchWidth) / 2, 25, searchWidth, 18, searchLabel));
        search.setHint(searchLabel);
        search.setResponder(query -> {
            populate(names, query);
            update();
        });
        populate(names, "");

        Component nameLabel = Component.translatable("reprise.entities.name_tag");
        this.nameTag = addRenderableWidget(new EditBox(this.font, this.width / 2 - 112, this.height - 73, 144, 18, nameLabel));
        this.nameTag.setMaxLength(64);
        this.nameTag.setHint(nameLabel);
        this.applyName = addRenderableWidget(Button.builder(Component.translatable("reprise.entities.set_name"), b -> applyName())
            .bounds(this.width / 2 + 36, this.height - 74, 76, 20).build());
        this.nameTag.setEditable(false);
        Component colorLabel = Component.translatable("reprise.entities.color");
        this.color = addRenderableWidget(new EditBox(this.font, this.width / 2 + 3, this.height - 50, 68, 18, colorLabel));
        this.color.setMaxLength(6);
        this.color.setFilter(value -> value.matches("[0-9A-Fa-f]{0,6}"));
        this.color.setValue("FFFFFF");
        this.color.setEditable(false);
        this.editing = null;
        int w = Math.min(94, (this.width - 16) / 4);
        int x = (this.width - 4 * w - 6) / 2;
        int y = this.height - 26;
        this.visibility = addRenderableWidget(Button.builder(Component.empty(), b -> toggleHidden()).bounds(x, y, w, 20).build());
        this.outline = addRenderableWidget(Button.builder(Component.empty(), b -> changeOutline()).bounds(x + w + 2, y, w, 20).build());
        this.spectate = addRenderableWidget(Button.builder(Component.translatable("reprise.entities.spectate"), b -> {
            Entity entity = this.minecraft.level.getEntity(this.list.getSelected().id);
            if (entity != null) {
                this.session.spectate(entity);
                this.minecraft.setScreen(null);
            }
        }).bounds(x + 2 * (w + 2), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 3 * (w + 2), y, w, 20).build());
        this.nameTag.setResponder(value -> update());
        this.color.setResponder(value -> update());
        update();
    }

    private void populate(Map<UUID, String> names, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        this.list.clearEntries();
        this.list.setScrollAmount(0);
        names.entrySet().stream()
            .filter(e -> displayName(e.getKey(), e.getValue()).toLowerCase(Locale.ROOT).contains(needle) || e.getKey().toString().contains(needle))
            .sorted(Map.Entry.<UUID, String>comparingByValue(String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry.comparingByKey()))
            .forEach(entry -> this.list.append(entry.getKey(), entry.getValue()));
    }

    private String displayName(UUID id, String name) {
        EntityOverride state = this.session.project().entityOverrides.get(id);
        return state == null || state.label() == null ? name : name + " · " + state.label();
    }

    private void toggleHidden() {
        Entry entry = this.list.getSelected();
        EntityOverride state = entry.override();
        this.session.project().setEntityOverride(entry.id, entry.name, state == null || !state.hidden(), state == null ? null : state.outlineColor(), state == null ? null : state.label());
        update();
    }

    private void applyName() {
        Entry entry = this.list.getSelected();
        EntityOverride state = entry.override();
        String label = this.nameTag.getValue().strip();
        this.session.project().setEntityOverride(entry.id, entry.name, state != null && state.hidden(),
            state == null ? null : state.outlineColor(), label.isEmpty() ? null : label);
        update();
    }

    private Integer selectedColor() {
        return this.color.getValue().length() == 6 ? Integer.parseInt(this.color.getValue(), 16) : null;
    }

    private void changeOutline() {
        Entry entry = this.list.getSelected();
        EntityOverride state = entry.override();
        Integer color = selectedColor();
        this.session.project().setEntityOverride(entry.id, entry.name, state != null && state.hidden(),
            state != null && color.equals(state.outlineColor()) ? null : color, state == null ? null : state.label());
        update();
    }

    @Override
    public void tick() {
        Entry entry = this.list.getSelected();
        this.spectate.active = entry != null && this.minecraft.level.getEntity(entry.id) != null;
    }

    private void update() {
        Entry entry = this.list.getSelected();
        EntityOverride state = entry == null ? null : entry.override();
        Integer outlineColor = state == null ? null : state.outlineColor();
        if (entry != this.editing) {
            this.editing = entry;
            this.color.setEditable(entry != null);
            this.nameTag.setEditable(entry != null);
            this.nameTag.setValue(state == null || state.label() == null ? "" : state.label());
            this.color.setValue(String.format(Locale.ROOT, "%06X", outlineColor == null ? 0xFFFFFF : outlineColor));
        }
        String savedLabel = state == null || state.label() == null ? "" : state.label();
        this.applyName.active = entry != null && !this.nameTag.getValue().strip().equals(savedLabel);
        this.visibility.active = entry != null;
        this.outline.active = entry != null && selectedColor() != null;
        this.spectate.active = entry != null && this.minecraft.level.getEntity(entry.id) != null;
        this.visibility.setMessage(Component.translatable(state != null && state.hidden() ? "reprise.entities.show" : "reprise.entities.hide"));
        String action = outlineColor == null ? "outline" : outlineColor.equals(selectedColor()) ? "clear_outline" : "apply_color";
        this.outline.setMessage(Component.translatable("reprise.entities." + action));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partial) {
        super.extractRenderState(g, x, y, partial);
        g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        Component label = Component.translatable("reprise.entities.color");
        g.text(this.font, label, this.width / 2 - 4 - this.font.width(label), this.height - 45, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private final class EntityList extends ObjectSelectionList<Entry> {
        EntityList(Minecraft mc) {
            super(mc, EntityScreen.this.width, EntityScreen.this.height - 131, 47, 28);
        }

        @Override
        public void setSelected(EntityScreen.Entry entry) {
            super.setSelected(entry);
            if (EntityScreen.this.list == this && EntityScreen.this.spectate != null) {
                update();
            }
        }

        void append(UUID id, String name) {
            addEntry(new EntityScreen.Entry(id, name));
        }
    }

    private final class Entry extends ObjectSelectionList.Entry<Entry> {
        final UUID id;
        final String name;

        Entry(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public Component getNarration() {
            return Component.literal(displayName(this.id, this.name)).append(", ").append(status());
        }

        EntityOverride override() {
            return session.project().entityOverrides.get(this.id);
        }

        private Component status() {
            EntityOverride state = override();
            if (state == null || !state.hidden() && state.outlineColor() == null) {
                return Component.translatable("reprise.entities.visible");
            }
            return Component.translatable(state.hidden() ? "reprise.entities.hidden" : "reprise.entities.outlined");
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int x, int y, boolean hovered, float partial) {
            int left = getContentX() + 4;
            Component status = status();
            int statusWidth = font.width(status);
            g.text(font, font.plainSubstrByWidth(displayName(this.id, this.name), getContentWidth() - statusWidth - 16), left, getContentY(), 0xFFFFFFFF);
            g.text(font, status, getContentRight() - statusWidth - 4, getContentY(), 0xFFE0E0E0);
            Entity entity = minecraft.level.getEntity(this.id);
            Component detail = entity == null ? Component.translatable("reprise.entities.unloaded")
                : Component.translatable("reprise.entities.distance", Math.round(entity.distanceTo(minecraft.player)));
            g.text(font, detail.copy().append("  ·  " + this.id.toString().substring(0, 8)), left, getContentY() + 11, 0xFFA0A0A0);
        }
    }
}
