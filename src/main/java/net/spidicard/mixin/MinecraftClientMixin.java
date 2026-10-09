package net.spidicard.mixin;

import net.minecraft.client.MinecraftClient;
import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    // Drain queued network callbacks first: a slot update must renew the quiet period
    // before we decide to close its inventory. Keep the normal tick as a fallback.
    // This invocation only occurs in render(true), not nested render(false) during loading.
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;runTasks()V", shift = At.Shift.AFTER))
    private void spidicard$afterPacketTasks(boolean tick, CallbackInfo ci) {
        if (SpidiCardClient.INSTANCE != null) SpidiCardClient.INSTANCE.pumpScan();
    }
}
