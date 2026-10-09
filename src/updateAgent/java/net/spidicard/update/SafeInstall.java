package net.spidicard.update;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class SafeInstall {
    private SafeInstall() {}
    public static String hash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static void validate(UpdatePlan plan) throws IOException {
        Path dir = plan.gameDir().toRealPath(), mods = dir.resolve("mods").toRealPath();
        Path target = plan.target().toRealPath(), staged = plan.staged().toRealPath();
        if (!mods.startsWith(dir) || !target.getParent().equals(mods) || Files.isSymbolicLink(plan.target())
                || !target.getFileName().toString().endsWith(".jar")
                || !dir.resolve(".spidicard").toRealPath().startsWith(dir)
                || !staged.startsWith(dir.resolve(".spidicard").toRealPath())
                || !plan.oldHash().matches("[a-f0-9]{64}") || !plan.newHash().matches("[a-f0-9]{64}"))
            throw new IOException("Update paths do not belong to the current instance");
        if (!hash(staged).equals(plan.newHash())) throw new IOException("Downloaded update checksum mismatch");
        if (!hash(target).equals(plan.oldHash())) throw new IOException("Installed mod changed during update");
    }
    /** Called only after the owning Minecraft JVM has exited and with the instance update lock held. */
    public static Path install(UpdatePlan plan) throws IOException {
        validate(plan);
        Path backups = plan.gameDir().resolve(".spidicard/backups");
        Files.createDirectories(backups);
        if (!backups.toRealPath().startsWith(plan.gameDir().toRealPath())) throw new IOException("External backup directory");
        Path backup = backups.resolve(plan.oldHash() + ".jar.bak");
        if (!Files.exists(backup)) Files.copy(plan.target(), backup);
        if (!hash(backup).equals(plan.oldHash())) throw new IOException("Backup checksum mismatch");
        Path temp = Files.createTempFile(plan.target().getParent(), ".spidicard-", ".tmp");
        try {
            Files.copy(plan.staged(), temp, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!hash(temp).equals(plan.newHash())) throw new IOException("Update copy checksum mismatch");
            // Recheck immediately before committing: never overwrite a user's newer installation.
            if (!hash(plan.target()).equals(plan.oldHash())) throw new IOException("Installed mod changed before replacement");
            try { Files.move(temp, plan.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, plan.target(), StandardCopyOption.REPLACE_EXISTING); }
            if (!hash(plan.target()).equals(plan.newHash())) {
                Files.copy(backup, plan.target(), StandardCopyOption.REPLACE_EXISTING);
                throw new IOException("Update verification failed; original restored");
            }
            Files.deleteIfExists(plan.staged());
            return backup;
        } finally { Files.deleteIfExists(temp); }
    }
}
