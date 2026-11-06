package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.ReplayFile;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.format.ReplayJoiner;
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
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Lists the replays in the replays folder. */
public final class ReplayBrowserScreen extends Screen {
    private final Screen parent;
    private final Path joinFirst;
    private final boolean inTrash;
    private Path selectOnOpen;
    private ReplayList list;
    private Button playButton;
    private Button deleteButton;
    private Button joinButton;
    private Button renameButton;
    private String outputName = "";
    private String query = "";
    private enum Order { NEWEST, NAME, LONGEST }
    private Order order = Order.NEWEST;

    public ReplayBrowserScreen(Screen parent) {
        this(parent, null, false);
    }

    private ReplayBrowserScreen(Screen parent, Path joinFirst, boolean inTrash) {
        super(joinFirst == null ? Component.translatable(inTrash ? "reprise.browser.trash" : "reprise.browser.title")
            : Component.translatable("reprise.browser.choose_next", joinFirst.getFileName().toString()));
        this.joinFirst = joinFirst;
        this.inTrash = inTrash;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int recovered = this.inTrash || this.joinFirst != null ? 0 : ReplayPacker.recoverAll();
        if (recovered > 0) {
            Reprise.LOGGER.info("Recovered {} unfinished recording(s)", recovered);
        }
        if (this.selectOnOpen != null) this.query = "";
        this.list = new ReplayList(this.minecraft, this.width, this.height - 116, 52, 36);
        this.addRenderableWidget(this.list);
        int y = this.height - 52;
        int w = 150;
        int x = this.width / 2 - w - 4;
        var searchLabel = Component.translatable("reprise.browser.search");
        var search = this.addRenderableWidget(new EditBox(this.font, x, 27, 200, 18, searchLabel));
        search.setHint(searchLabel);
        search.setValue(this.query);
        search.setResponder(value -> {
            this.query = value;
            this.list.filter();
        });
        this.addRenderableWidget(Button.builder(orderLabel(), button -> {
            this.order = Order.values()[(this.order.ordinal() + 1) % Order.values().length];
            button.setMessage(orderLabel());
            this.list.filter();
        }).bounds(x + 204, 26, 104, 20).build());
        if (this.inTrash) {
            this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.restore"), b -> restore())
                .bounds(x, y, w, 20).build());
            this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.erase"), b -> erase())
                .bounds(x + w + 8, y, w, 20).build());
        } else if (this.joinFirst == null) {
            this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.play"), b -> play())
                .bounds(x, y, 96, 20).build());
            this.joinButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.join"), b ->
                this.minecraft.setScreen(new ReplayBrowserScreen(this, this.list.getSelected().path, false)))
                .bounds(x + 100, y, 108, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.import"), b -> chooseImport())
                .bounds(x + 212, y, 96, 20).build());
            this.renameButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.rename_short"), b ->
                this.minecraft.setScreen(new RenameReplayScreen(this, this.list.getSelected().path, path -> this.selectOnOpen = path)))
                .bounds(x, y + 24, 54, 20).build());
            this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.move_to_trash"), b -> moveToTrash())
                .bounds(x + 58, y + 24, 80, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.trash"), b ->
                this.minecraft.setScreen(new ReplayBrowserScreen(this, null, true))).bounds(x + 142, y + 24, 44, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.folder"), b -> Util.getPlatform().openPath(ReprisePaths.replays()))
                .bounds(x + 190, y + 24, 66, 20).build());
        } else {
            var label = Component.translatable("reprise.browser.join_name");
            var name = this.addRenderableWidget(new EditBox(this.font, x, y, 2 * w + 8, 20, label));
            name.setMaxLength(60);
            name.setHint(label);
            name.setValue(this.outputName);
            name.setResponder(value -> this.outputName = value);
            this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.join_selected"), b -> play())
                .bounds(x, y + 24, w, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(x + 260, y + 24, 48, 20).build());
        this.list.filter();
        if (this.selectOnOpen != null) {
            this.list.children().stream().filter(entry -> entry.path.equals(this.selectOnOpen)).findFirst().ifPresent(this.list::setSelected);
            this.selectOnOpen = null;
        }
        updateButtons();
    }

    private Component orderLabel() {
        return Component.translatable("reprise.browser.order." + this.order.name().toLowerCase(Locale.ROOT));
    }

    private void updateButtons() {
        boolean selected = this.list.getSelected() != null;
        boolean readable = selected && this.list.getSelected().meta != null;
        this.playButton.active = this.inTrash ? selected : readable;
        if (this.deleteButton != null) this.deleteButton.active = selected;
        if (this.renameButton != null) this.renameButton.active = selected;
        if (this.joinButton != null) this.joinButton.active = readable && this.list.readableCount > 1;
    }

    private void play() {
        ReplayList.ReplayEntry entry = this.list.getSelected();
        if (entry != null) {
            if (this.inTrash) {
                restore();
            } else if (entry.meta == null) {
                return;
            } else if (this.joinFirst == null) {
                ReplaySession.open(entry.path, this);
            } else {
                join(entry.path);
            }
        }
    }

    private void chooseImport() {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                Component.translatable("reprise.browser.import").getString(), "", stack.pointers(stack.UTF8("*.zip"), stack.UTF8("*.mcpr")), "Flashback ZIP or ReplayMod MCPR", false);
            if (path != null) importRecording(Path.of(path));
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (!this.inTrash && this.joinFirst == null && files.size() == 1) importRecording(files.getFirst());
    }

    private void importRecording(Path source) {
        var client = this.minecraft;
        var progress = new GenericMessageScreen(Component.translatable("reprise.browser.importing"));
        client.setScreen(progress);
        Thread.startVirtualThread(() -> {
            try {
                Path imported = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpr")
                    ? com.metrovoc.reprise.format.ReplayModImport.convert(source, ReprisePaths.replays())
                    : com.metrovoc.reprise.format.FlashbackImport.convert(source, ReprisePaths.replays());
                client.execute(() -> {
                    if (client.screen == progress) {
                        this.selectOnOpen = imported;
                        client.setScreen(this);
                    }
                });
            } catch (Exception e) {
                Reprise.LOGGER.warn("Could not import {}", source, e);
                client.execute(() -> {
                    if (client.screen == progress) client.setScreen(new AlertScreen(() -> client.setScreen(this),
                        Component.translatable("reprise.browser.import_failed"), Component.literal(String.valueOf(e.getMessage()))));
                });
            }
        });
    }

    private void join(Path second) {
        var client = this.minecraft;
        var progress = new GenericMessageScreen(Component.translatable("reprise.browser.joining"));
        client.setScreen(progress);
        String name = this.outputName;
        Thread.startVirtualThread(() -> {
            try {
                Path result = ReplayJoiner.join(this.joinFirst, second, name);
                client.execute(() -> {
                    if (client.screen == progress) {
                        ((ReplayBrowserScreen) this.parent).selectOnOpen = result;
                        client.setScreen(this.parent);
                    }
                });
            } catch (Exception e) {
                Reprise.LOGGER.warn("Could not join recordings", e);
                client.execute(() -> {
                    if (client.screen == progress) client.setScreen(new AlertScreen(() -> client.setScreen(this),
                        Component.translatable("reprise.browser.join_failed"), Component.literal(String.valueOf(e.getMessage()))));
                });
            }
        });
    }

    private void moveToTrash() {
        var entry = this.list.getSelected();
        if (entry == null) return;
        try {
            moveRecording(entry.path, ReprisePaths.trash());
            rebuildWidgets();
        } catch (IOException e) {
            fileError(entry.path, e);
        }
    }

    private void restore() {
        var entry = this.list.getSelected();
        if (entry == null) return;
        try {
            ((ReplayBrowserScreen) this.parent).selectOnOpen = moveRecording(entry.path, ReprisePaths.replays());
            this.minecraft.setScreen(this.parent);
        } catch (IOException e) {
            fileError(entry.path, e);
        }
    }

    private static Path moveRecording(Path source, Path destination) throws IOException {
        String filename = source.getFileName().toString();
        String stem = filename.substring(0, filename.length() - ReplayPacker.EXTENSION.length());
        for (int i = 1; ; i++) {
            Path target = destination.resolve(i == 1 ? filename : stem + " (" + i + ")" + ReplayPacker.EXTENSION);
            try {
                return Files.move(source, target);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A newer recording can have the original name.
            }
        }
    }

    private void erase() {
        var entry = this.list.getSelected();
        if (entry == null) return;
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    Files.delete(entry.path);
                } catch (IOException e) {
                    fileError(entry.path, e);
                    return;
                }
            }
            this.minecraft.setScreen(this);
        }, Component.translatable("reprise.browser.delete.question"), Component.translatable("reprise.browser.erase_detail", entry.path.getFileName().toString())));
    }

    private void fileError(Path path, IOException e) {
        Reprise.LOGGER.warn("Could not update {}", path, e);
        this.minecraft.setScreen(new AlertScreen(() -> this.minecraft.setScreen(this),
            Component.translatable("reprise.browser.file_error"), Component.literal(String.valueOf(e.getMessage()))));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (this.list.children().isEmpty()) {
            graphics.centeredText(this.font, Component.translatable(!this.query.isBlank() ? "reprise.browser.no_matches" : this.inTrash ? "reprise.browser.trash_empty" : "reprise.browser.empty"), this.width / 2, this.height / 2 - 20, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    final class ReplayList extends ObjectSelectionList<ReplayList.ReplayEntry> {
        private final List<ReplayEntry> all = new ArrayList<>();
        private int readableCount;

        ReplayList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            try (Stream<Path> files = Files.list(inTrash ? ReprisePaths.trash() : ReprisePaths.replays())) {
                for (Path path : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(ReplayPacker.EXTENSION)).toList()) {
                    if (path.equals(joinFirst)) continue;
                    try {
                        all.add(new ReplayEntry(path, ReplayFile.readMeta(path)));
                        readableCount++;
                    } catch (Exception e) {
                        Reprise.LOGGER.warn("Unreadable replay {}", path, e);
                        if (joinFirst == null) all.add(new ReplayEntry(path, null));
                    }
                }
            } catch (IOException e) {
                Reprise.LOGGER.warn("Could not list replays", e);
            }
        }

        private void filter() {
            ReplayEntry selected = getSelected();
            String term = query.strip().toLowerCase(Locale.ROOT);
            Comparator<ReplayEntry> byName = Comparator.comparing(e -> e.path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
            Comparator<ReplayEntry> comparator = switch (order) {
                case NAME -> byName;
                case NEWEST -> Comparator.comparingLong((ReplayEntry e) -> e.meta == null ? Long.MIN_VALUE : e.meta.recordedAt).reversed().thenComparing(byName);
                case LONGEST -> Comparator.comparingInt((ReplayEntry e) -> e.meta == null ? -1 : e.meta.durationTicks).reversed().thenComparing(byName);
            };
            replaceEntries(all.stream().filter(e -> e.searchText.contains(term)).sorted(comparator).toList());
            setScrollAmount(0);
            setSelected(children().contains(selected) ? selected : null);
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
            final String searchText;

            ReplayEntry(Path path, ReplayMeta meta) {
                this.path = path;
                this.meta = meta;
                String details = meta == null ? "" : meta.name + "\n" + meta.server + "\n" + meta.mcVersion;
                this.searchText = (path.getFileName() + "\n" + details).toLowerCase(Locale.ROOT);
            }

            @Override
            public Component getNarration() {
                String file = this.path.getFileName().toString();
                return Component.literal(this.meta == null || file.equals(this.meta.name + ReplayPacker.EXTENSION)
                    ? file : this.meta.name + ", " + file);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                int x = getContentX() + 2;
                int y = getContentY() + 1;
                String name = this.path.getFileName().toString();
                name = name.substring(0, name.length() - ReplayPacker.EXTENSION.length());
                graphics.text(ReplayBrowserScreen.this.font, name, x, y, 0xFFFFFFFF);
                if (this.meta == null) {
                    graphics.text(ReplayBrowserScreen.this.font, Component.translatable("reprise.browser.unreadable"), x, y + 11, 0xFFFF8888);
                    return;
                }
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
