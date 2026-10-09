package net.spidicard.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {
    @Shadow @Final protected T handler;

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"), cancellable = true)
    private void spidicard$blockTransfer(Slot slot, int slotId, int button, SlotActionType action, CallbackInfo ci) {
        if (SpidiCardClient.INSTANCE != null && SpidiCardClient.INSTANCE.owns(handler.syncId)) ci.cancel();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void spidicard$keys(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (SpidiCardClient.INSTANCE != null && SpidiCardClient.INSTANCE.owns(handler.syncId)) {
            if (key == 256) SpidiCardClient.INSTANCE.stopFromScreen();
            cir.setReturnValue(true);
        }
    }
}
