package net.spidicard.mixin;

import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.fabricmc.fabric.impl.command.client.ClientCommandInternals", remap = false)
public abstract class ClientCommandMixin {
    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true, remap = false)
    private static void spidicard$localFeedback(String command, CallbackInfoReturnable<Boolean> ci) {
        if (SpidiCardClient.INSTANCE != null && SpidiCardClient.INSTANCE.handleLocalCommand(command))
            ci.setReturnValue(true);
    }
}
