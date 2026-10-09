package net.spidicard.update;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Private stdin protocol: launch arguments, including authentication, are never written to disk. */
public record UpdatePlan(long parentPid, Path gameDir, Path target, Path staged,
                         String oldHash, String newHash, Path workingDir, List<String> command) {
    public UpdatePlan { command = List.copyOf(command); }
    public void write(OutputStream stream) throws IOException {
        var out = new DataOutputStream(stream);
        out.writeInt(0x53434431); out.writeLong(parentPid);
        for (String value : List.of(gameDir.toString(), target.toString(), staged.toString(),
                oldHash, newHash, workingDir.toString())) out.writeUTF(value);
        out.writeInt(command.size());
        for (String arg : command) out.writeUTF(arg);
        out.flush();
    }
    public static UpdatePlan read(InputStream stream) throws IOException {
        var in = new DataInputStream(stream);
        if (in.readInt() != 0x53434431) throw new IOException("Unknown update protocol");
        long pid = in.readLong();
        Path dir = Path.of(in.readUTF()), target = Path.of(in.readUTF()), staged = Path.of(in.readUTF());
        String oldHash = in.readUTF(), newHash = in.readUTF();
        Path cwd = Path.of(in.readUTF());
        int count = in.readInt();
        if (pid <= 0 || count < 0 || count > 4096) throw new IOException("Invalid update plan");
        var args = new ArrayList<String>();
        for (int i = 0; i < count; i++) args.add(in.readUTF());
        return new UpdatePlan(pid, dir, target, staged, oldHash, newHash, cwd, args);
    }
}
