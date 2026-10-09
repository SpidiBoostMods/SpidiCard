package net.spidicard.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onEnterReconfiguration", at = @At("TAIL"))
    private void spidicard$configuration(EnterReconfigurationS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.worldChanged();
    }

    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void spidicard$position(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.positionChanged();
    }
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void spidicard$join(GameJoinS2CPacket packet, CallbackInfo ci) { SpidiCardClient.INSTANCE.worldChanged(); }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void spidicard$respawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) { SpidiCardClient.INSTANCE.worldChanged(); }

    @Inject(method = "onPlayerList", at = @At("TAIL"))
    private void spidicard$tab(PlayerListS2CPacket packet, CallbackInfo ci) { SpidiCardClient.INSTANCE.tabChanged(); }

    @Inject(method = "onPlayerRemove", at = @At("TAIL"))
    private void spidicard$remove(PlayerRemoveS2CPacket packet, CallbackInfo ci) { SpidiCardClient.INSTANCE.tabChanged(); }
    // TAIL runs after vanilla's forceMainThread and after applying the packet.
    @Inject(method = "onOpenScreen", at = @At("TAIL"))
    private void spidicard$open(OpenScreenS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.opened(packet.getSyncId(), packet.getName().getString());
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void spidicard$contents(InventoryS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.contents(packet.getSyncId(), packet.getContents().size());
    }

    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void spidicard$slot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.slotUpdated(packet.getSyncId(), packet.getSlot());
    }

    @Inject(method = "onCloseScreen", at = @At("TAIL"))
    private void spidicard$close(CloseScreenS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.serverClosed(packet.getSyncId());
    }

    @Inject(method = "onGameMessage", at = @At("TAIL"))
    private void spidicard$message(GameMessageS2CPacket packet, CallbackInfo ci) {
        SpidiCardClient.INSTANCE.serverMessage(packet.content().getString(), packet.overlay());
    }
}
