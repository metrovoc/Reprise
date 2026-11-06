package com.metrovoc.reprise.record;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;
import net.neoforged.neoforge.network.payload.CommonRegisterPayload;
import net.neoforged.neoforge.network.payload.CommonVersionPayload;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ReplayPayloads {
    private ReplayPayloads() {}

    public static boolean records(CustomPacketPayload payload) {
        if (!NetworkRegistry.isModdedPayload(payload) || payload instanceof CommonVersionPayload || payload instanceof CommonRegisterPayload) {
            return false;
        }
        var id = payload.type().id();
        // Simple Voice Chat uses its play channels to authenticate a separate live voice connection.
        if (id.getNamespace().equals("voicechat")) {
            return false;
        }
        if (id.getNamespace().equals("neoforge")) {
            return payload instanceof AdvancedAddEntityPayload || payload instanceof AuxiliaryLightDataPayload
                || payload instanceof SyncAttachmentsPayload;
        }
        return true;
    }
}
