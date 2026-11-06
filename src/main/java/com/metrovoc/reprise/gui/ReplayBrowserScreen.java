package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.Reprise;
import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.format.ModDifferences;
import com.metrovoc.reprise.format.ReplayFile;
import com.metrovoc.reprise.format.ReplayMeta;
import com.metrovoc.reprise.playback.ReplaySession;
import com.metrovoc.reprise.record.ReplayPacker;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * The replay library. Each row shows a picture from the recording, where it was recorded, when, how long and how big,
 * and a warning line when something needs attention (mods changed, updates missing, another game version). Double-click
 * or Enter watches; deleting moves to a Trash that can be restored from. Other formats import by dropping the file here.
 */
public final class ReplayBrowserScreen extends Screen {
    /** File names the recorder gives by default; such rows are titled by world or server instead. */
    private static final Pattern DEFAULT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}.*");
    private static final int ROW_HEIGHT = 40;
    private static final int THUMB_W = 64, THUMB_H = 36;
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(5000L);

    private final Screen parent;
    private final boolean inTrash;
    private Path selectOnOpen;
    private ReplayList list;
    private Button playButton;
    private Button deleteButton;
    private Button renameButton;
    private String query = "";
    private enum Order { NEWEST, NAME, LONGEST }
    private Order order = Order.NEWEST;
    private final Map<Path, Identifier> thumbnails = new HashMap<>();

    public ReplayBrowserScreen(Screen parent) {
        this(parent, false);
    }

    private ReplayBrowserScreen(Screen parent, boolean inTrash) {
        super(Component.translatable(inTrash ? "reprise.browser.trash" : "reprise.browser.title"));
        this.inTrash = inTrash;
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!this.inTrash && ReplayPacker.recoverAll() > 0) {
            Reprise.LOGGER.info("Recovered unfinished recordings");
        }
        if (this.selectOnOpen != null) this.query = "";
        int listTop = 50;
        int listBottom = this.height - 70;
        this.list = new ReplayList(this.minecraft, this.width, listBottom - listTop, listTop, ROW_HEIGHT);
        this.addRenderableWidget(this.list);

        int rowWidth = 320;
        int x = this.width / 2 - rowWidth / 2;
        var searchLabel = Component.translatable("reprise.browser.search");
        var search = this.addRenderableWidget(new EditBox(this.font, x, 26, rowWidth - 106, 18, searchLabel));
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
        }).bounds(x + rowWidth - 102, 25, 102, 20).build());

        int y = this.height - 50;
        if (this.inTrash) {
            this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.restore"), b -> restore())
                .bounds(x, y, 156, 20).build());
            this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.erase"), b -> erase())
                .bounds(x + 164, y, 156, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 82, y + 24, 156, 20).build());
        } else {
            this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.play"), b -> play())
                .bounds(x, y, 156, 20).build());
            this.renameButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.rename_short"), b ->
                this.minecraft.setScreen(new RenameReplayScreen(this, this.list.getSelected().path, path -> this.selectOnOpen = path)))
                .bounds(x + 164, y, 76, 20).build());
            this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.delete"), b -> moveToTrash())
                .bounds(x + 244, y, 76, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.folder"), b -> Util.getPlatform().openPath(ReprisePaths.replays()))
                .bounds(x, y + 24, 104, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("reprise.browser.trash"), b ->
                this.minecraft.setScreen(new ReplayBrowserScreen(this, true))).bounds(x + 108, y + 24, 104, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 216, y + 24, 104, 20).build());
        }
        this.list.filter();
        if (this.selectOnOpen != null) {
            this.list.children().stream().filter(entry -> entry.path.equals(this.selectOnOpen)).findFirst().ifPresent(this.list::setSelected);
            this.selectOnOpen = null;
        } else if (!this.inTrash && !this.list.children().isEmpty()) {
            // The newest recording is what people come for most often.
            this.list.setSelected(this.list.children().getFirst());
        }
        updateButtons();
    }

    private Component orderLabel() {
        return Component.translatable("reprise.browser.order." + this.order.name().toLowerCase(Locale.ROOT));
    }

    private void updateButtons() {
        var entry = this.list.getSelected();
        this.playButton.active = entry != null && (this.inTrash || entry.playable());
        this.deleteButton.active = entry != null;
        if (this.renameButton != null) this.renameButton.active = entry != null;
    }

    private void play() {
        var entry = this.list.getSelected();
        if (entry == null) return;
        if (this.inTrash) {
            restore();
        } else if (entry.playable()) {
            ReplaySession.open(entry.path, this);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && this.list.getSelected() != null) {
            play();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE && this.list.getSelected() != null && !(getFocused() instanceof EditBox)) {
            if (this.inTrash) erase();
            else moveToTrash();
            return true;
        }
        return super.keyPressed(event);
    }

    // Importing: drop a Flashback ZIP or ReplayMod MCPR onto the library.

    @Override
    public void onFilesDrop(List<Path> files) {
        if (!this.inTrash && files.size() == 1) importRecording(files.getFirst());
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

    // Trash

    private void moveToTrash() {
        var entry = this.list.getSelected();
        if (entry == null) return;
        try {
            moveRecording(entry.path, ReprisePaths.trash());
            SystemToast.addOrUpdate(this.minecraft.getToastManager(), TOAST, Component.translatable("reprise.browser.trashed", entry.title()),
                Component.translatable("reprise.browser.trashed.detail"));
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
        }, Component.translatable("reprise.browser.delete.question"), Component.translatable("reprise.browser.erase_detail", entry.title())));
    }

    private void fileError(Path path, IOException e) {
        Reprise.LOGGER.warn("Could not update {}", path, e);
        this.minecraft.setScreen(new AlertScreen(() -> this.minecraft.setScreen(this),
            Component.translatable("reprise.browser.file_error"), Component.literal(String.valueOf(e.getMessage()))));
    }

    // Drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        if (this.list.children().isEmpty()) {
            String key = !this.query.isBlank() ? "reprise.browser.no_matches" : this.inTrash ? "reprise.browser.trash_empty" : "reprise.browser.empty";
            graphics.centeredText(this.font, Component.translatable(key), this.width / 2, this.height / 2 - 20, 0xFFA0A0A0);
        }
        if (!this.inTrash) {
            graphics.centeredText(this.font, Component.translatable("reprise.browser.drop_hint"), this.width / 2, this.height - 64, 0xFF707078);
        }
    }

    @Override
    public void removed() {
        // Thumbnails live only while the library is open.
        this.thumbnails.values().forEach(id -> { if (id != null) this.minecraft.getTextureManager().release(id); });
        this.thumbnails.clear();
        super.removed();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private Identifier thumbnail(Path path) {
        return this.thumbnails.computeIfAbsent(path, p -> {
            try (ReplayFile file = new ReplayFile(p)) {
                byte[] bytes = file.entry("thumb.png");
                if (bytes == null) return null;
                NativeImage image = NativeImage.read(bytes);
                Identifier id = Identifier.fromNamespaceAndPath(Reprise.MOD_ID, "thumbnail/" + Integer.toHexString(p.hashCode()));
                this.minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Reprise thumbnail", image));
                return id;
            } catch (Exception e) {
                return null;
            }
        });
    }

    static String relativeDate(long epochMillis) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
        LocalDate day = time.toLocalDate(), today = LocalDate.now();
        String clock = time.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (day.equals(today)) return Component.translatable("reprise.browser.today", clock).getString();
        if (day.equals(today.minusDays(1))) return Component.translatable("reprise.browser.yesterday", clock).getString();
        String pattern = day.getYear() == today.getYear() ? "MMM d, HH:mm" : "MMM d yyyy, HH:mm";
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()));
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        return mb < 100 ? String.format(Locale.ROOT, "%.1f MB", mb) : Math.round(mb) + " MB";
    }

    final class ReplayList extends ObjectSelectionList<ReplayList.ReplayEntry> {
        private final List<ReplayEntry> all = new ArrayList<>();

        ReplayList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            try (Stream<Path> files = Files.list(inTrash ? ReprisePaths.trash() : ReprisePaths.replays())) {
                for (Path path : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(ReplayPacker.EXTENSION)).toList()) {
                    ReplayMeta meta = null;
                    try {
                        meta = ReplayFile.readMeta(path);
                    } catch (Exception e) {
                        Reprise.LOGGER.warn("Unreadable replay {}", path, e);
                    }
                    all.add(new ReplayEntry(path, meta));
                }
            } catch (IOException e) {
                Reprise.LOGGER.warn("Could not list replays", e);
            }
        }

        private void filter() {
            ReplayEntry selected = getSelected();
            String term = query.strip().toLowerCase(Locale.ROOT);
            Comparator<ReplayEntry> byName = Comparator.comparing(ReplayEntry::title, String.CASE_INSENSITIVE_ORDER);
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
            private final String title;
            private final String details;
            private final String issue;
            private final boolean blocking;

            ReplayEntry(Path path, ReplayMeta meta) {
                this.path = path;
                this.meta = meta;
                String file = path.getFileName().toString();
                String stem = file.substring(0, file.length() - ReplayPacker.EXTENSION.length());
                String place = meta == null || meta.server == null || meta.server.isBlank() ? "" : meta.server;
                // A renamed recording keeps its name; a default one is called by where it was recorded.
                this.title = DEFAULT_NAME.matcher(stem).matches() && !place.isEmpty() ? place : stem;
                long bytes;
                try {
                    bytes = Files.size(path);
                } catch (IOException e) {
                    bytes = 0;
                }
                if (meta == null) {
                    this.details = size(bytes);
                    this.issue = Component.translatable("reprise.browser.unreadable").getString();
                    this.blocking = true;
                } else {
                    String where = this.title.equals(place) || place.isEmpty() ? "" : "  ·  " + place;
                    this.details = relativeDate(meta.recordedAt) + "  ·  " + Timecode.format(meta.durationTicks) + "  ·  " + size(bytes) + where;
                    if (meta.protocol != SharedConstants.getProtocolVersion()) {
                        this.issue = Component.translatable("reprise.browser.other_version", meta.mcVersion).getString();
                        this.blocking = false;
                    } else if (meta.unrecordedCount() > 0) {
                        this.issue = Component.translatable("reprise.browser.unrecorded", meta.unrecordedCount()).getString();
                        this.blocking = false;
                    } else {
                        List<String> mods = ModDifferences.describe(meta);
                        this.issue = mods.isEmpty() ? null : Component.translatable("reprise.browser.mods_changed", String.join(", ", mods)).getString();
                        this.blocking = false;
                    }
                }
                this.searchText = (stem + "\n" + this.title + "\n" + (meta == null ? "" : meta.server + "\n" + meta.mcVersion + "\n" + meta.recorderName))
                    .toLowerCase(Locale.ROOT);
            }

            String title() {
                return this.title;
            }

            boolean playable() {
                return this.meta != null && !this.blocking;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.title + ", " + this.details);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                var font = ReplayBrowserScreen.this.font;
                int x = getContentX();
                int y = getContentY();
                Identifier thumb = thumbnail(this.path);
                if (thumb != null) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, thumb, x, y, 0, 0, THUMB_W, THUMB_H, THUMB_W, THUMB_H);
                } else {
                    graphics.fill(x, y, x + THUMB_W, y + THUMB_H, 0xFF26262E);
                    graphics.centeredText(font, "▶", x + THUMB_W / 2, y + THUMB_H / 2 - 4, 0xFF55555F);
                }
                int tx = x + THUMB_W + 6;
                int maxWidth = getContentWidth() - THUMB_W - 8;
                graphics.text(font, fit(this.title, maxWidth), tx, y + 1, 0xFFFFFFFF);
                graphics.text(font, fit(this.details, maxWidth), tx, y + 13, 0xFFA0A0A8);
                if (this.issue != null) {
                    graphics.text(font, fit("⚠ " + this.issue, maxWidth), tx, y + 25, this.blocking ? 0xFFFF7070 : 0xFFFFC14D);
                }
            }

            private String fit(String text, int width) {
                var font = ReplayBrowserScreen.this.font;
                return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…";
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                ReplayList.this.setSelected(this);
                if (doubleClick) play();
                return true;
            }
        }
    }
}
