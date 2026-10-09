package net.spidicard.update;

import java.io.*;
import java.net.URI;
import java.util.Properties;

public record ReleaseManifest(String version, String artifact, String sha256) {
    public static final String REPOSITORY = "https://github.com/SpidiBoostMods/SpidiCard";
    public static final URI LATEST = URI.create(REPOSITORY + "/releases/latest/download/spidicard-update.properties");
    public static ReleaseManifest parse(String text) throws IOException {
        var props = new Properties(); props.load(new StringReader(text));
        String version = props.getProperty("version", ""), artifact = props.getProperty("artifact", "");
        String hash = props.getProperty("sha256", "");
        if (!validVersion(version) || !"1.21.4".equals(props.getProperty("minecraft"))
                || !artifact.equals("SpidiCard-1.21.4-" + version + ".jar") || !hash.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid or incompatible SpidiCard release manifest");
        return new ReleaseManifest(version, artifact, hash);
    }
    public URI download() { return URI.create(REPOSITORY + "/releases/download/v" + version + "/" + artifact); }
    public boolean newerThan(String installed) {
        if (!validVersion(installed)) return false;
        String[] a = version.split("\\."), b = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }
    private static boolean validVersion(String version) {
        return version.matches("(?:0|[1-9][0-9]{0,5})\\.(?:0|[1-9][0-9]{0,5})\\.(?:0|[1-9][0-9]{0,5})");
    }
}
