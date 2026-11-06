package com.metrovoc.reprise.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A list of actions that opens above (or below) a button, closes on any click outside it, and shows shortcuts. */
public final class PopupMenu {
    private static final int ROW = 13;
    private static final int PAD = 3;
    private static final int SEPARATOR = 5;

    private record Item(Component label, String shortcut, Runnable action, boolean enabled, boolean checked) {}

    private final List<Object> entries = new ArrayList<>();
    private int x, y, width, height;

    public PopupMenu add(Component label, String shortcut, boolean enabled, Runnable action) {
        entries.add(new Item(label, shortcut, action, enabled, false));
        return this;
    }

    public PopupMenu add(Component label, Runnable action) {
        return add(label, null, true, action);
    }

    public PopupMenu check(Component label, boolean checked, Runnable action) {
        entries.add(new Item(label, null, action, true, checked));
        return this;
    }

    public PopupMenu separator() {
        if (!entries.isEmpty() && !(entries.getLast() instanceof String)) entries.add("-");
        return this;
    }

    /** Places the menu with its bottom edge at {@code bottom} (or top at {@code top} if it would not fit above). */
    public PopupMenu place(Font font, int anchorX, int bottom, int screenWidth, int screenHeight) {
        width = 0;
        height = PAD * 2;
        for (Object e : entries) {
            if (e instanceof Item item) {
                int w = 14 + font.width(item.label) + (item.shortcut == null ? 0 : 16 + font.width(item.shortcut));
                width = Math.max(width, w + PAD * 2);
                height += ROW;
            } else {
                height += SEPARATOR;
            }
        }
        x = Math.clamp(anchorX, 2, Math.max(2, screenWidth - width - 2));
        y = bottom - height;
        if (y < 2) y = Math.min(bottom + 22, screenHeight - height - 2);
        return this;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        g.nextStratum();
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF3C3C4A);
        g.fill(x, y, x + width, y + height, 0xF5191920);
        int ty = y + PAD;
        for (Object e : entries) {
            if (e instanceof Item item) {
                boolean hover = item.enabled && mouseX >= x && mouseX < x + width && mouseY >= ty && mouseY < ty + ROW;
                if (hover) g.fill(x + 1, ty, x + width - 1, ty + ROW, 0xFF2E4470);
                int color = item.enabled ? 0xFFE8E8EE : 0xFF6A6A74;
                if (item.checked) g.text(font, "✔", x + PAD + 1, ty + 3, 0xFF8FD18F, false);
                g.text(font, item.label, x + PAD + 12, ty + 3, color, false);
                if (item.shortcut != null) {
                    g.text(font, item.shortcut, x + width - PAD - 2 - font.width(item.shortcut), ty + 3, 0xFF7E7E8C, false);
                }
                ty += ROW;
            } else {
                g.fill(x + 4, ty + 2, x + width - 4, ty + 3, 0xFF34343F);
                ty += SEPARATOR;
            }
        }
    }

    /** Runs the first enabled item whose label starts with {@code prefix}; used by scripted tests. */
    public boolean choose(String prefix) {
        for (Object e : entries) {
            if (e instanceof Item item && item.enabled && item.label.getString().startsWith(prefix)) {
                item.action.run();
                return true;
            }
        }
        return false;
    }

    /** Runs the item under the pointer; returns true when the click landed in the menu. */
    public boolean click(double mx, double my) {
        if (!contains(mx, my)) return false;
        int ty = y + PAD;
        for (Object e : entries) {
            if (e instanceof Item item) {
                if (my >= ty && my < ty + ROW) {
                    if (item.enabled) item.action.run();
                    return true;
                }
                ty += ROW;
            } else {
                ty += SEPARATOR;
            }
        }
        return true;
    }
}
