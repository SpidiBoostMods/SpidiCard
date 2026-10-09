package net.spidicard;

import java.util.function.Supplier;

/** Resolve the server's current profile every time; never cache the launch-session username. */
public final class ScanExclusions {
    private final Supplier<String> liveSelf;
    public ScanExclusions(Supplier<String> liveSelf) { this.liveSelf = liveSelf; }
    public boolean excludes(String name) {
        if (name == null) return true;
        String self = liveSelf.get();
        return "SpidiBoost".equalsIgnoreCase(name) || (self != null && self.equalsIgnoreCase(name));
    }
}
