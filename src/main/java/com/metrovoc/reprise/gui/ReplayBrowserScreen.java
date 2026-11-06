package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.ReplayFile;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.playback.ReplaySession;
import com.metrovoc.reprise.record.ReplayPacker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Lists the replays in the replays folder. */
public final class ReplayBrowserScreen extends Screen {
    private final Screen parent;
    private ReplayList list;
    private Button playButton;
    private Button deleteButton;

    public ReplayBrowserScreen(Screen parent) {
        super(Component.translatable("reprise.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int recovered = ReplayPacker.recoverAll();
        if (recovered > 0) {
            Reprise.LOGGER.info("Recovered {} unfinished recording(s)", recovered);
        }
        this.list = new ReplayList(this.minecraft, this.width, this.height - 96, 32, 36);
        this.addRenderableWidget(this.list);
        int y = this.height - 52;
        int w = 150;
        int x = this.width / 2 - w - 4;
        this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.play"), b -> play())
            .bounds(x, y, w, 20).build());
        this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("selectWorld.delete"), b -> delete())
            .bounds(x + w + 8, y, w, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.folder"), b -> Util.getPlatform().openPath(ReprisePaths.replays()))
            .bounds(x, y + 24, w, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(x + w + 8, y + 24, w, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        boolean selected = this.list.getSelected() != null;
        this.playButton.active = selected;
        this.deleteButton.active = selected;
    }

    private void play() {
        ReplayList.ReplayEntry entry = this.list.getSelected();
        if (entry != null) {
            ReplaySession.open(entry.path, this);
        }
    }

    private void delete() {
        ReplayList.ReplayEntry entry = this.list.getSelected();
        if (entry == null) {
            return;
        }
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    Files.deleteIfExists(entry.path);
                } catch (IOException e) {
                    Reprise.LOGGER.warn("Could not delete {}", entry.path, e);
                }
            }
            this.minecraft.setScreen(this);
        }, Component.translatable("reprise.browser.delete.question"), Component.literal(entry.meta.name)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (this.list.children().isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("reprise.browser.empty"), this.width / 2, this.height / 2 - 20, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    final class ReplayList extends ObjectSelectionList<ReplayList.ReplayEntry> {
        ReplayList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            List<ReplayEntry> entries = new ArrayList<>();
            try (Stream<Path> files = Files.list(ReprisePaths.replays())) {
                for (Path path : files.filter(p -> p.toString().endsWith(ReplayPacker.EXTENSION)).toList()) {
                    try {
                        entries.add(new ReplayEntry(path, ReplayFile.readMeta(path)));
                    } catch (Exception e) {
                        Reprise.LOGGER.warn("Unreadable replay {}", path, e);
                    }
                }
            } catch (IOException e) {
                Reprise.LOGGER.warn("Could not list replays", e);
            }
            entries.sort(Comparator.comparingLong((ReplayEntry e) -> e.meta.recordedAt).reversed());
            entries.forEach(this::addEntry);
        }

        @Override
        public int getRowWidth() {
            return 320;
        }

        @Override
        public void setSelected(ReplayEntry entry) {
            super.setSelected(entry);
            updateButtons();
        }

        final class ReplayEntry extends ObjectSelectionList.Entry<ReplayEntry> {
            final Path path;
            final ReplayMeta meta;
            private long lastClick;

            ReplayEntry(Path path, ReplayMeta meta) {
                this.path = path;
                this.meta = meta;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.meta.name);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                int x = getContentX() + 2;
                int y = getContentY() + 1;
                String name = this.path.getFileName().toString();
                name = name.substring(0, name.length() - ReplayPacker.EXTENSION.length());
                graphics.text(ReplayBrowserScreen.this.font, name, x, y, 0xFFFFFFFF);
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(this.meta.recordedAt));
                String line = date + "  ·  " + Timecode.format(this.meta.durationTicks) + "  ·  " + this.meta.mcVersion;
                graphics.text(ReplayBrowserScreen.this.font, line, x, y + 11, 0xFFA0A0A0);
                if (!this.meta.server.isEmpty()) {
                    graphics.text(ReplayBrowserScreen.this.font, this.meta.server, x, y + 22, 0xFF808080);
                }
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                ReplayList.this.setSelected(this);
                if (doubleClick) {
                    play();
                }
                return true;
            }
        }
    }
}
