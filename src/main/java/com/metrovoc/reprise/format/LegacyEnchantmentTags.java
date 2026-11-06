package com.metrovoc.reprise.format;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.Identifier;

/** In 1.21 a server could send enchantments referencing absent tags. Newer registry codecs require a binding. */
final class LegacyEnchantmentTags {
    static final int UPDATE_TAGS = 13;
    private final Map<String, Set<Identifier>> references = new LinkedHashMap<>();
    private final Map<String, Map<Identifier, IntList>> recorded = new LinkedHashMap<>();

    void inspect(ClientboundRegistryDataPacket packet) {
        if (!packet.registry().identifier().toString().equals("minecraft:enchantment")) return;
        for (var entry : packet.entries()) {
            if (entry.data().orElse(null) instanceof CompoundTag enchantment) {
                reference("minecraft:item", enchantment, "supported_items");
                reference("minecraft:item", enchantment, "primary_items");
                reference("minecraft:enchantment", enchantment, "exclusive_set");
                inspectConditions(enchantment.get("effects"));
            }
        }
    }

    private void inspectConditions(Tag node) {
        if (node instanceof CompoundTag compound) {
            if (compound.getStringOr("condition", "").equals("minecraft:location_check")) {
                var predicate = compound.getCompoundOrEmpty("predicate");
                reference("minecraft:block", predicate.getCompoundOrEmpty("block"), "blocks");
            }
            for (var field : compound.entrySet()) inspectConditions(field.getValue());
        } else if (node instanceof ListTag list) {
            list.forEach(this::inspectConditions);
        }
    }

    private void reference(String registry, CompoundTag compound, String field) {
        String value = compound.getStringOr(field, "");
        if (value.startsWith("#")) {
            references.computeIfAbsent(registry, ignored -> new LinkedHashSet<>()).add(Identifier.parse(value.substring(1)));
        }
    }

    void record(FriendlyByteBuf buf) {
        recorded.putAll(buf.readMap(FriendlyByteBuf::readUtf,
            in -> in.readMap(FriendlyByteBuf::readIdentifier, FriendlyByteBuf::readIntIdList)));
    }

    byte[] missingBindings() {
        boolean changed = false;
        for (var entry : references.entrySet()) {
            var tags = recorded.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
            for (var name : entry.getValue()) {
                if (tags.putIfAbsent(name, IntList.of()) == null) changed = true;
            }
        }
        if (!changed) return null;
        var out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            out.writeVarInt(UPDATE_TAGS);
            // Via emits additional tag groups even for a partial update. Keep the complete recorded bindings.
            out.writeMap(recorded, FriendlyByteBuf::writeUtf, (buf, tags) ->
                buf.writeMap(tags, FriendlyByteBuf::writeIdentifier, FriendlyByteBuf::writeIntIdList));
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }
}
