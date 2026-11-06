package com.metrovoc.reprise.export;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ExportScreen extends Screen {
    private final Screen parent;
    private final ReplaySession session;

    public ExportScreen(Screen parent, ReplaySession session) {
        super(Component.translatable("reprise.export.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
