package net.spidicard.update;

import java.nio.file.*;
import java.util.*;

public final class RestartCommand {
    private RestartCommand() {}
    public static List<String> detect(Path gameDir) {
        ProcessHandle process = ProcessHandle.current();
        for (int i = 0; i < 12; i++) {
            var parent = process.parent(); if (parent.isEmpty()) break;
            process = parent.get();
            String executable = process.info().command().orElse("");
            var prism = prism(gameDir, executable);
            if (!prism.isEmpty()) return prism;
        }
        var info = ProcessHandle.current().info();
        return direct(info.command().orElse(""), info.arguments().orElse(new String[0]));
    }
    public static List<String> prism(Path gameDir, String executable) {
        if (executable.isEmpty()) return List.of();
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.equals("prismlauncher") && !name.equals("prismlauncher.exe")) return List.of();
        Path game = gameDir.toAbsolutePath().normalize();
        if (game.getFileName() == null || !(game.getFileName().toString().equals("minecraft")
                || game.getFileName().toString().equals(".minecraft"))) return List.of();
        Path instance = game.getParent(), instances = instance.getParent();
        if (instances == null || !instances.getFileName().toString().equals("instances")
                || !Files.isRegularFile(instance.resolve("instance.cfg"))) return List.of();
        return List.of(executable, "--dir", instances.getParent().toString(), "--launch", instance.getFileName().toString());
    }
    public static List<String> direct(String executable, String[] arguments) {
        if (executable.isEmpty()) return List.of();
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Set.of("java", "java.exe", "javaw.exe").contains(name)) return List.of();
        // Launcher wrappers and @argument files can require live IPC or get deleted on exit.
        if (Arrays.stream(arguments).anyMatch(arg -> arg.startsWith("@"))) return List.of();
        boolean knot = Arrays.asList(arguments).contains("net.fabricmc.loader.impl.launch.knot.KnotClient")
                || Arrays.asList(arguments).contains("net.fabricmc.loader.launch.knot.KnotClient");
        if (!knot) return List.of();
        var command = new ArrayList<String>(); command.add(executable); command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
}
