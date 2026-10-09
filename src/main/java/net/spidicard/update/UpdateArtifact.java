package net.spidicard.update;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public final class UpdateArtifact {
    private UpdateArtifact() {}
    public static void validate(Path file, ReleaseManifest manifest) throws IOException {
        if (!SafeInstall.hash(file).equals(manifest.sha256())) throw new IOException("Release checksum mismatch");
        try (var zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("Not a Fabric mod");
            byte[] json;
            try (var in = zip.getInputStream(entry)) { json = in.readNBytes(65537); }
            if (json.length > 65536) throw new IOException("Mod metadata too large");
            var metadata = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"spidicard".equals(metadata.get("id").getAsString())
                    || !manifest.version().equals(metadata.get("version").getAsString())
                    || !"client".equals(metadata.get("environment").getAsString())
                    || !"~1.21.4".equals(metadata.getAsJsonObject("depends").get("minecraft").getAsString())
                    || zip.getEntry("spidicard-update-agent.jar") == null)
                throw new IOException("Unexpected SpidiCard mod identity or compatibility");
        } catch (RuntimeException e) { throw new IOException("Invalid mod metadata", e); }
    }
}
