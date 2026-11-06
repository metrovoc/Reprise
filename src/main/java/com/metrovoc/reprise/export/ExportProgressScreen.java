package com.metrovoc.reprise.export;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown between exported frames; Escape or the button cancels. */
final class ExportProgressScreen extends Screen {
    private final VideoExporter exporter;

    ExportProgressScreen(VideoExporter exporter) {
        super(Component.translatable("reprise.export.progress"));
        this.exporter = exporter;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.exporter.cancel())
            .bounds(this.width / 2 - 50, this.height / 2 + 30, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        super.extractRenderState(g, mouseX, mouseY, a);
        int done = this.exporter.frame(), total = this.exporter.totalFrames();
        int barW = Math.min(300, this.width - 40);
        int x = (this.width - barW) / 2, y = this.height / 2;
        g.fill(x, y, x + barW, y + 6, 0xFF2A2A33);
        g.fill(x, y, x + (int) ((long) barW * done / Math.max(1, total)), y + 6, 0xFF5B8CFF);
        g.centeredText(this.font, this.title, this.width / 2, y - 30, 0xFFFFFFFF);
        double seconds = this.exporter.elapsedMillis() / 1000.0;
        double rate = seconds > 0 ? done / seconds : 0;
        long eta = rate > 0 ? Math.round((total - done) / rate) : 0;
        String line = Component.translatable("reprise.export.progress.line", done, total, String.format("%.1f", rate), eta).getString();
        g.centeredText(this.font, line, this.width / 2, y - 16, 0xFFB0B0B8);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    }

    @Override
    public void onClose() {
        this.exporter.cancel();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
