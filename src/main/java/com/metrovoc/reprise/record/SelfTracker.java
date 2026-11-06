package com.metrovoc.reprise.record;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The recording player as other players see them. A server never tells a player about their own entity, so it is
 * spawned and kept up to date from the local player: position, head, entity data, equipment and swings.
 */
final class SelfTracker {
    private final Recorder recorder;
    private boolean spawnPending;
    private Vec3 lastPos;
    private float lastYRot, lastXRot, lastHeadRot;
    private boolean lastOnGround;
    private final Map<Integer, Object> lastData = new java.util.HashMap<>();
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private final boolean recordHotbar = com.metrovoc.reprise.RepriseConfig.RECORD_HOTBAR.get();
    private final ItemStack[] lastHotbar = new ItemStack[net.minecraft.world.entity.player.Inventory.getSelectionSize()];
    private int lastSelected;
    private float lastHealth, lastSaturation, lastExperience;
    private int lastFood, lastLevel, lastTotalExperience;
    private boolean wasSwinging;
    private int lastSwingTime;
    private int spawnedId = Integer.MIN_VALUE;

    SelfTracker(Recorder recorder) {
        this.recorder = recorder;
    }

    /** The client got a new player entity (login or respawn); spawn it again at the end of this tick. */
    void respawned() {
        this.spawnPending = true;
    }

    /** Spawns the player from scratch; called while writing a snapshot. */
    void spawn() {
        LocalPlayer player = Minecraft.getInstance().player;
        this.spawnPending = false;
        this.lastData.clear();
        this.lastEquipment.clear();
        this.spawnedId = player.getId();
        this.recorder.writeGame(new ClientboundAddEntityPacket(
            player.getId(), player.getUUID(), player.getX(), player.getY(), player.getZ(),
            player.getXRot(), player.getYRot(), EntityType.PLAYER, 0, player.getDeltaMovement(), player.getYHeadRot()));
        List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
        if (data != null) {
            data.forEach(value -> this.lastData.put(value.id(), value.value()));
            this.recorder.writeGame(new ClientboundSetEntityDataPacket(player.getId(), data));
        }
        var attributes = player.getAttributes().getSyncableAttributes();
        if (!attributes.isEmpty()) {
            this.recorder.writeGame(new ClientboundUpdateAttributesPacket(player.getId(), attributes));
        }
        player.getActiveEffects().forEach(effect -> this.recorder.writeGame(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false)));
        writeHotbar(player, true);
        writeEquipment(player, true);
        rememberPose(player);
    }

    void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (this.spawnPending || player.getId() != this.spawnedId) {
            if (this.spawnedId != Integer.MIN_VALUE) {
                this.recorder.writeGame(new ClientboundRemoveEntitiesPacket(this.spawnedId));
            }
            spawn();
            return;
        }
        Vec3 pos = player.position();
        if (!pos.equals(this.lastPos) || player.getYRot() != this.lastYRot || player.getXRot() != this.lastXRot
            || player.onGround() != this.lastOnGround) {
            this.recorder.writeGame(ClientboundEntityPositionSyncPacket.of(player));
        }
        if (player.getYHeadRot() != this.lastHeadRot) {
            this.recorder.writeGame(new ClientboundRotateHeadPacket(player, (byte) Mth.floor(player.getYHeadRot() * 256.0F / 360.0F)));
        }
        rememberPose(player);
        writeDataChanges(player);
        writeHotbar(player, false);
        writeEquipment(player, false);
        writeSwing(player);
    }

    private void rememberPose(LocalPlayer player) {
        this.lastPos = player.position();
        this.lastYRot = player.getYRot();
        this.lastXRot = player.getXRot();
        this.lastHeadRot = player.getYHeadRot();
        this.lastOnGround = player.onGround();
    }

    private void writeDataChanges(LocalPlayer player) {
        List<SynchedEntityData.DataValue<?>> changed = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : player.getEntityData().itemsById) {
            if (item == null) {
                continue;
            }
            int id = item.getAccessor().id();
            Object value = item.getValue();
            if (!dataEquals(this.lastData.get(id), value) && !(item.isSetToDefault() && !this.lastData.containsKey(id))) {
                changed.add(item.value());
            }
            if (item.isSetToDefault()) {
                this.lastData.remove(id);
            } else {
                this.lastData.put(id, copy(value));
            }
        }
        if (!changed.isEmpty()) {
            this.recorder.writeGame(new ClientboundSetEntityDataPacket(player.getId(), changed));
        }
    }

    private static boolean dataEquals(Object a, Object b) {
        if (a instanceof ItemStack x && b instanceof ItemStack y) {
            return ItemStack.matches(x, y);
        }
        return Objects.equals(a, b);
    }

    private static Object copy(Object value) {
        return value instanceof ItemStack stack ? stack.copy() : value;
    }

    private void writeHotbar(LocalPlayer player, boolean snapshot) {
        if (!this.recordHotbar) return;
        var food = player.getFoodData();
        if (snapshot || player.getHealth() != lastHealth || food.getFoodLevel() != lastFood || food.getSaturationLevel() != lastSaturation) {
            lastHealth = player.getHealth();
            lastFood = food.getFoodLevel();
            lastSaturation = food.getSaturationLevel();
            this.recorder.writeGame(new ClientboundSetHealthPacket(lastHealth, lastFood, lastSaturation));
        }
        if (snapshot || player.experienceProgress != lastExperience || player.experienceLevel != lastLevel || player.totalExperience != lastTotalExperience) {
            lastExperience = player.experienceProgress;
            lastLevel = player.experienceLevel;
            lastTotalExperience = player.totalExperience;
            this.recorder.writeGame(new ClientboundSetExperiencePacket(lastExperience, lastTotalExperience, lastLevel));
        }
        var inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (snapshot || selected != this.lastSelected) {
            this.recorder.writeGame(new ClientboundSetHeldSlotPacket(selected));
            this.lastSelected = selected;
        }
        for (int i = 0; i < this.lastHotbar.length; i++) {
            ItemStack item = inventory.getItem(i);
            if (snapshot || !ItemStack.matches(item, this.lastHotbar[i])) {
                if (!snapshot || !item.isEmpty()) this.recorder.writeGame(new ClientboundSetPlayerInventoryPacket(i, item));
                this.lastHotbar[i] = item.copy();
            }
        }
    }

    private void writeEquipment(LocalPlayer player, boolean all) {
        List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            ItemStack last = this.lastEquipment.getOrDefault(slot, ItemStack.EMPTY);
            if (all ? !stack.isEmpty() : !ItemStack.matches(stack, last)) {
                ItemStack saved = stack.copy();
                changed.add(Pair.of(slot, saved));
                this.lastEquipment.put(slot, saved);
            }
        }
        if (!changed.isEmpty()) {
            this.recorder.writeGame(new ClientboundSetEquipmentPacket(player.getId(), changed));
        }
    }

    private void writeSwing(LocalPlayer player) {
        boolean started = player.swinging && (!this.wasSwinging || player.swingTime < this.lastSwingTime);
        if (started) {
            int action = player.swingingArm == InteractionHand.OFF_HAND ? ClientboundAnimatePacket.SWING_OFF_HAND : ClientboundAnimatePacket.SWING_MAIN_HAND;
            this.recorder.writeGame(new ClientboundAnimatePacket(player, action));
        }
        this.wasSwinging = player.swinging;
        this.lastSwingTime = player.swingTime;
    }
}
