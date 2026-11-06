package com.metrovoc.reprise.mixin.record;

import com.metrovoc.reprise.record.Recorder;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    // The displayed component already contains the sender and any server or client decoration.
    @Unique
    private static final ChatTypeDecoration reprise$contentOnly = new ChatTypeDecoration("%s",
        List.of(ChatTypeDecoration.Parameter.CONTENT), Style.EMPTY);
    @Unique
    private static final ChatType.Bound reprise$displayed = new ChatType.Bound(
        Holder.direct(new ChatType(reprise$contentOnly, reprise$contentOnly)), Component.empty(), Optional.empty());

    /** Capture the final player message after validation, filtering, mod hooks and chat delay. */
    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void reprise$recordDisplayedChat(GuiMessage message, CallbackInfo ci) {
        Recorder recorder = Recorder.active();
        if (recorder != null && message.source() == GuiMessageSource.PLAYER) {
            recorder.recordLocal(new ClientboundDisguisedChatPacket(message.content(), reprise$displayed));
        }
    }
}
