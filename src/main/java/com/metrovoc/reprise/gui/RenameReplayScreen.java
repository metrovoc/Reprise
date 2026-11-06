package com.metrovoc.reprise.gui;

import com.metrovoc.reprise.record.ReplayPacker;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;

final class RenameReplayScreen extends Screen {
    private final Screen parent;
    private final Path source;
    private final Consumer<Path> renamed;
    private String value;
    private Component error = Component.empty();

    RenameReplayScreen(Screen parent, Path source, Consumer<Path> renamed) {
        super(Component.translatable("reprise.browser.rename"));
        this.parent = parent;
        this.source = source;
        this.renamed = renamed;
        String filename = source.getFileName().toString();
        this.value = filename.substring(0, filename.length() - ReplayPacker.EXTENSION.length());
    }

    @Override
    protected void init() {
        int x = width / 2 - 120, y = height / 2 - 18;
        var input = addRenderableWidget(new EditBox(font, x, y, 240, 20, Component.translatable("reprise.browser.name")));
        input.setMaxLength(255);
        input.setValue(value);
        input.setHighlightPos(0);
        setInitialFocus(input);
        var save = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> rename())
            .bounds(x, y + 42, 118, 20).build());
        save.active = !value.isBlank();
        input.setResponder(text -> {
            value = text;
            save.active = !text.isBlank();
            error = Component.empty();
        });
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(x + 122, y + 42, 118, 20).build());
    }

    private void rename() {
        String stem = value.strip();
        if (stem.isEmpty() || stem.equals(".") || stem.equals("..") || stem.endsWith(".")
            || stem.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
            || !FileUtil.isPathPartPortable(stem + ReplayPacker.EXTENSION)) {
            error = Component.translatable("reprise.browser.invalid_name");
            return;
        }
        Path target = source.resolveSibling(stem + ReplayPacker.EXTENSION);
        try {
            if (!source.equals(target) && source.getFileName().toString().equalsIgnoreCase(target.getFileName().toString())
                && Files.exists(target) && Files.isSameFile(source, target)) {
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(source, target);
            }
            renamed.accept(target);
            onClose();
        } catch (FileAlreadyExistsException e) {
            error = Component.translatable("reprise.browser.name_taken");
        } catch (IOException e) {
            error = Component.literal(e.getLocalizedMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, height / 2 - 36, 0xFFFFFFFF);
        g.centeredText(font, error, width / 2, height / 2 + 8, 0xFFFF8888);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
