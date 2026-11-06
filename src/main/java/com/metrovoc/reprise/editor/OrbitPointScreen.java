package com.metrovoc.reprise.editor;

import com.metrovoc.reprise.playback.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class OrbitPointScreen extends Screen {
    private final EditorScreen parent;
    private final ReplaySession session;
    private final EditBox[] coordinates = new EditBox[3];
    private Button add;

    OrbitPointScreen(EditorScreen parent, ReplaySession session) {
        super(Component.translatable("reprise.editor.orbit_center"));
        this.parent = parent;
        this.session = session;
        session.setPlaying(false);
    }

    @Override
    protected void init() {
        var camera = this.minecraft.player;
        Vec3 eye = camera.getEyePosition(), look = camera.getLookAngle();
        var hit = this.minecraft.level.clip(new ClipContext(eye, eye.add(look.scale(16 * this.minecraft.options.getEffectiveRenderDistance())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        Vec3 block = hit.getType() == HitResult.Type.BLOCK ? Vec3.atCenterOf(hit.getBlockPos()) : null;
        Vec3 center = block == null ? eye.add(look.scale(8)) : block;
        for (Keyframe key : this.session.project().keyframes) {
            if (key.tick > this.session.tick()) {
                break;
            }
            if (key.target != null && key.target.id() == null) {
                center = new Vec3(key.target.x(), key.target.y(), key.target.z());
            }
        }
        int x = this.width / 2 - 110, y = this.height / 2;
        String[] labels = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            this.coordinates[i] = addRenderableWidget(new EditBox(this.font, x + 75 * i, y - 14, 70, 20, Component.literal(labels[i])));
        }
        setCenter(center);
        Button pick = addRenderableWidget(Button.builder(Component.translatable("reprise.editor.pick_block"), b -> setCenter(block))
            .bounds(x, y + 12, 108, 20).build());
        pick.active = block != null;
        this.add = addRenderableWidget(Button.builder(Component.translatable("reprise.entities.orbit_key"), b -> {
            Vec3 value = value();
            if (value != null) {
                this.parent.addTargetKey(new Keyframe.Target(null, String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", value.x, value.y, value.z),
                    Keyframe.Tracking.ORBIT, Keyframe.AimPoint.CENTER, value.x, value.y, value.z));
                onClose();
            }
        }).bounds(x + 112, y + 12, 108, 20).build());
        for (EditBox box : this.coordinates) {
            box.setResponder(text -> this.add.active = value() != null);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x, y + 36, 220, 20).build());
    }

    private void setCenter(Vec3 value) {
        this.coordinates[0].setValue(Double.toString(value.x));
        this.coordinates[1].setValue(Double.toString(value.y));
        this.coordinates[2].setValue(Double.toString(value.z));
    }

    private Vec3 value() {
        try {
            double x = Double.parseDouble(this.coordinates[0].getValue());
            double y = Double.parseDouble(this.coordinates[1].getValue());
            double z = Double.parseDouble(this.coordinates[2].getValue());
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) ? new Vec3(x, y, z) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partial) {
        super.extractRenderState(g, x, y, partial);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 54, 0xFFFFFFFF);
        for (EditBox box : this.coordinates) {
            g.text(this.font, box.getMessage(), box.getX(), box.getY() - 12, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
