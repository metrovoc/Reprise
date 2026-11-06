package com.metrovoc.reprise.playback;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * First-person hands for a spectated player. Vanilla draws hands only for the local player, so a stand-in local
 * player mirrors the spectated one (pose, held items, swing, item use) and takes the local player's place while the
 * hand renderer ticks and draws.
 */
public final class PovHands {
    private static Stand stand;
    private static LocalPlayer swappedOut;
    private static GameType swappedMode;

    /** The spectated player whose hands are drawn, or null. */
    private static @Nullable AbstractClientPlayer target() {
        Minecraft mc = Minecraft.getInstance();
        if (!ReplaySession.isActive() || mc.player == null || mc.level == null) {
            return null;
        }
        return mc.getCameraEntity() instanceof AbstractClientPlayer p && p != mc.player && !p.isSpectator() ? p : null;
    }

    /** Puts the stand-in in place; returns false (and changes nothing) when no player is spectated. */
    public static boolean begin() {
        AbstractClientPlayer target = target();
        if (target == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (stand == null || stand.level() != mc.level || stand.target != target) {
            stand = new Stand(mc, target);
        }
        stand.copyFrom(target);
        swappedOut = mc.player;
        mc.player = stand;
        swappedMode = mc.gameMode.localPlayerMode;
        mc.gameMode.localPlayerMode = GameType.SURVIVAL;
        return true;
    }

    public static void end() {
        Minecraft mc = Minecraft.getInstance();
        if (swappedOut != null) {
            mc.player = swappedOut;
            mc.gameMode.localPlayerMode = swappedMode;
            swappedOut = null;
        }
    }

    /** Mixed into the hand renderer, so a seek can put the hands straight in place without the re-equip animation. */
    public interface Snappable {
        void reprise$snap(net.minecraft.world.item.ItemStack main, net.minecraft.world.item.ItemStack off);
    }

    public static void snap() {
        AbstractClientPlayer target = target();
        if (target != null) {
            ((Snappable) Minecraft.getInstance().gameRenderer.itemInHandRenderer).reprise$snap(target.getMainHandItem(), target.getOffhandItem());
        }
    }

    /** Hand sway follows the view with a lag, updated once per tick as vanilla does. */
    public static void tick() {
        AbstractClientPlayer target = target();
        if (stand != null && target != null && stand.target == target) {
            stand.yBobO = stand.yBob;
            stand.xBobO = stand.xBob;
            stand.xBob += (target.getXRot() - stand.xBob) * 0.5F;
            stand.yBob += (target.getYRot() - stand.yBob) * 0.5F;
        }
    }

    private static final class Stand extends LocalPlayer {
        final AbstractClientPlayer target;

        Stand(Minecraft mc, AbstractClientPlayer target) {
            super(mc, mc.level, mc.getConnection(), new StatsCounter(), new ClientRecipeBook(), Input.EMPTY, false, mc.computeChatAbilities());
            this.target = target;
            this.xBob = this.xBobO = target.getXRot();
            this.yBob = this.yBobO = target.getYRot();
        }

        @Override
        protected @Nullable PlayerInfo getPlayerInfo() {
            // The spectated player's skin and arm model.
            return Minecraft.getInstance().getConnection().getPlayerInfo(this.target.getUUID());
        }

        void copyFrom(AbstractClientPlayer t) {
            this.setPos(t.position());
            this.xo = t.xo;
            this.yo = t.yo;
            this.zo = t.zo;
            this.setYRot(t.getYRot());
            this.setXRot(t.getXRot());
            this.yRotO = t.yRotO;
            this.xRotO = t.xRotO;
            this.setItemSlot(EquipmentSlot.MAINHAND, t.getMainHandItem());
            this.setItemSlot(EquipmentSlot.OFFHAND, t.getOffhandItem());
            this.swinging = t.swinging;
            this.swingTime = t.swingTime;
            this.swingingArm = t.swingingArm;
            this.attackAnim = t.attackAnim;
            this.oAttackAnim = t.oAttackAnim;
            var data = t.getEntityData().getNonDefaultValues();
            if (data != null) {
                this.getEntityData().assignValues(data);
            }
            this.setInvisible(t.isInvisible());
            // A stand-in never ticks; a full attack cooldown keeps vanilla from lowering the hand.
            this.attackStrengthTicker = 1000;
            this.itemSwapTicker = 1000;
        }
    }

    private PovHands() {}
}
