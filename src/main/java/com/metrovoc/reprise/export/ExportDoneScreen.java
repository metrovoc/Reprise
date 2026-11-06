package com.metrovoc.reprise.export;

import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

final class ExportDoneScreen extends Screen {
    private final Screen parent;
    private final boolean ok;
    private final Component message;
    private final Path output;

    ExportDoneScreen(Screen parent, boolean ok, Component message, Path output) {
        super(Component.translatable(ok ? "reprise.export.done.title" : "reprise.export.failed.title"));
        this.parent = parent;
        this.ok = ok;
        this.message = message;
        this.output = output;
    }

    @Override
    protected void init() {
        int y = this.height / 2 + 10;
        if (this.ok) {
            addRenderableWidget(Button.builder(Component.translatable("reprise.browser.folder"),
                b -> Util.getPlatform().openPath(this.output.getParent())).bounds(this.width / 2 - 102, y, 100, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(this.ok ? this.width / 2 + 2 : this.width / 2 - 50, y, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
        g.centeredText(this.font, this.message, this.width / 2, this.height / 2 - 14, this.ok ? 0xFFB0B0B8 : 0xFFFF6060);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
