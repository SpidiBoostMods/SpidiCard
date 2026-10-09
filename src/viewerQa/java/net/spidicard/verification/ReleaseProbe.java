package net.spidicard.verification;

import net.spidicard.update.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Validates the public release using the very same downloader and checks as the game. */
public final class ReleaseProbe {
    public static void main(String[] args) throws Exception {
        var download = new GitHubDownload();
        var manifest = ReleaseManifest.parse(new String(download.get(ReleaseManifest.LATEST, 16384), StandardCharsets.UTF_8));
        if (!manifest.version().equals("1.4.0") || !manifest.newerThan("1.3.9") || manifest.newerThan("1.4.0"))
            throw new AssertionError("Latest release version mismatch");
        Path directory = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(directory);
        Path file = directory.resolve(manifest.artifact());
        Files.write(file, download.get(manifest.download(), 16 * 1024 * 1024));
        UpdateArtifact.validate(file, manifest);
        Files.writeString(directory.resolve("public-release-results.txt"), "PASS public latest manifest and redirects\nPASS numeric upgrade/no-repeat detection\nPASS public JAR download and SHA-256\nPASS actual mod identity, version, Minecraft, helper metadata\nSHA-256 " + manifest.sha256() + "\nALL PUBLIC RELEASE CHECKS PASSED\n");
        System.out.println("Public GitHub release verified: " + manifest.sha256());
    }
}
