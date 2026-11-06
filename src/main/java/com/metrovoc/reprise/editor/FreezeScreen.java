package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class FreezeScreen extends Screen {
    private final EditorScreen parent;
    private final ReplaySession session;
    private EditBox length;

    FreezeScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.editor.freeze"));
        this.parent = parent;
        this.session = session;
        session.setPlaying(false);
    }

    @Override
    protected void init() {
        int x = width / 2 - 90, y = height / 2;
        length = addRenderableWidget(new EditBox(font, x, y - 15, 180, 20,
            Component.translatable("reprise.editor.freeze_length")));
        length.setMaxLength(9);
        length.setFilter(s -> s.matches("[0-9]*"));
        length.setValue("20");
        var insert = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.insert_freeze"), b -> {
            int tick = session.tick();
            session.project().insertFreeze(tick, Integer.parseInt(length.getValue()));
            session.seek(tick);
            onClose();
        }).bounds(x, y + 12, 88, 20).build());
        length.setResponder(s -> insert.active = !s.isEmpty() && Integer.parseInt(s) > 0
            && (long) session.duration() + Integer.parseInt(s) <= Integer.MAX_VALUE);
        insert.active = session.duration() <= Integer.MAX_VALUE - 20;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(x + 92, y + 12, 88, 20).build());
        setInitialFocus(length);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, Component.translatable("reprise.editor.freeze_length"), width / 2, height / 2 - 32, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
