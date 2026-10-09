package net.spidicard.verification;

import net.spidicard.FullRun;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;

/** Assert cleanup in the actual client after normal completion, stop and disconnect. */
final class MemoryLifecycleProbe {
    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    static void assertReleased() throws Exception {
        var mod = SpidiCardClient.INSTANCE;
        var scan = (ScanSession) field(mod, "session");
        if (scan != null && !scan.active()) {
            for (String name : new String[]{"scanConnection", "scanCardCounter", "scanNameCache"}) {
                if (field(mod, name) != null) throw new AssertionError("Finished scan retains " + name);
            }
        }
        var full = (FullRun) field(mod, "fullRun");
        if (full != null && !full.active()) {
            if (field(mod, "fullConnection") != null) throw new AssertionError("Finished full retains transport");
            if (field(field(mod, "tabSnapshots"), "connection") != null)
                throw new AssertionError("Finished full retains TAB connection");
        }
    }
}
