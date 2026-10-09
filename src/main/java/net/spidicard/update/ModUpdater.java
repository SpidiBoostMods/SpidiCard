package net.spidicard.update;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.spidicard.SpidiCardClient;
import net.spidicard.shared.SharedUpdater;

/** Compatibility entrypoint; the family coordinator owns every check and restart. */
public final class ModUpdater {
    public static void initialize() {
        SharedUpdater.preserve("spidicard",()->SpidiCardClient.INSTANCE.stopForUpdate());
        SharedUpdater.initialize();
    }
    public static void registerChecks(Runnable check) {
        ClientLifecycleEvents.CLIENT_STARTED.register(ignored->check.run());
        ClientPlayConnectionEvents.JOIN.register((handler,sender,ignored)->check.run());
    }
}
