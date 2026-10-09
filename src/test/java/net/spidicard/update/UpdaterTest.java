package net.spidicard.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {
    @TempDir Path root;
    String manifest(String version) { return "version=" + version + "\nminecraft=1.21.4\nartifact=SpidiCard-1.21.4-" + version + ".jar\nsha256=" + "a".repeat(64); }
    @Test void comparesNumericallyAndRejectsDowngrades() throws Exception {
        var release = ReleaseManifest.parse(manifest("1.10.0"));
        assertTrue(release.newerThan("1.9.99")); assertFalse(release.newerThan("1.10.0"));
        assertFalse(release.newerThan("2.0.0")); assertFalse(release.newerThan("1.9.0-dev"));
        assertEquals("https://github.com/SpidiBoostMods/SpidiCard/releases/download/v1.10.0/SpidiCard-1.21.4-1.10.0.jar", release.download().toString());
    }
    @Test void rejectsWrongMinecraftVersionsPathsAndChecksums() {
        for (String text : List.of(manifest("1.4.0").replace("minecraft=1.21.4", "minecraft=1.21.5"),
                manifest("1.4.0").replace("artifact=SpidiCard", "artifact=../SpidiCard"),
                manifest("1.4.0").replace("sha256=", "sha256=X"), manifest("1.04.0"),
                manifest("2147483648.0.0"), manifest("1.4.0") + "\nartifact=https://evil.example/mod.jar"))
            assertThrows(IOException.class, () -> ReleaseManifest.parse(text));
    }
    @Test void trustBoundaryRejectsHttpUserInfoSubdomainsAndForeignPorts() {
        for (String uri : List.of("http://github.com/x", "https://github.com.evil.example/x", "https://evil.example/x",
                "https://user@github.com/x", "https://github.com:444/x")) assertFalse(GitHubDownload.trusted(URI.create(uri)));
        assertTrue(GitHubDownload.trusted(URI.create("https://release-assets.githubusercontent.com/x?signature=abc")));
    }
    @Test void capsBodyBeforeAllocatingOversizedChunk() {
        var body = new GitHubDownload.LimitedBody(4);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled.set(true); } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[3]), ByteBuffer.wrap(new byte[2])));
        assertTrue(cancelled.get()); assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    UpdatePlan fixture() throws Exception {
        Path dir = root.resolve("Инстанс & spaces"), mods = dir.resolve("mods"), stage = dir.resolve(".spidicard");
        Files.createDirectories(mods); Files.createDirectories(stage);
        Path target = mods.resolve("старый мод.jar"), candidate = stage.resolve("new.jar");
        Files.writeString(target, "old"); Files.writeString(candidate, "new");
        return new UpdatePlan(ProcessHandle.current().pid(), dir, target, candidate, SafeInstall.hash(target), SafeInstall.hash(candidate), dir, List.of());
    }
    @Test void installsAtOriginalFilenameBacksUpAndPreservesOtherInstanceFiles() throws Exception {
        var plan = fixture(); Files.writeString(plan.gameDir().resolve("spidicard.txt"), "nick1 nick2");
        Files.writeString(plan.target().getParent().resolve("other.jar"), "other");
        Path backup = SafeInstall.install(plan);
        assertEquals("old", Files.readString(backup)); assertEquals("new", Files.readString(plan.target()));
        assertFalse(Files.exists(plan.staged())); assertEquals("nick1 nick2", Files.readString(plan.gameDir().resolve("spidicard.txt")));
        assertEquals("other", Files.readString(plan.target().getParent().resolve("other.jar")));
        try (var files = Files.list(plan.target().getParent())) { assertEquals(2, files.count()); }
    }
    @Test void refusesTamperedDownloadWithoutChangingOriginal() throws Exception {
        var plan = fixture(); Files.writeString(plan.staged(), "bad");
        assertThrows(IOException.class, () -> SafeInstall.install(plan)); assertEquals("old", Files.readString(plan.target()));
    }
    @Test void refusesReplacingUsersNewerManualInstallation() throws Exception {
        var plan = fixture(); Files.writeString(plan.target(), "manually installed newer mod");
        assertThrows(IOException.class, () -> SafeInstall.install(plan));
        assertEquals("manually installed newer mod", Files.readString(plan.target()));
    }
    @Test void rejectsAnotherInstancesTarget() throws Exception {
        var plan = fixture(); Path foreign = root.resolve("foreign.jar"); Files.writeString(foreign, "old");
        var bad = new UpdatePlan(plan.parentPid(), plan.gameDir(), foreign, plan.staged(), plan.oldHash(), plan.newHash(), root, List.of());
        assertThrows(IOException.class, () -> SafeInstall.install(bad)); assertEquals("old", Files.readString(foreign));
    }
    @Test void preservesUnicodeSpacesAndPrivateArgumentsOverStdinProtocol() throws Exception {
        var plan = fixture(); var args = List.of("C:\\Java 21\\bin\\java.exe", "--accessToken", "test-token", "Имя & ник $ ' (тест)");
        var original = new UpdatePlan(plan.parentPid(), plan.gameDir(), plan.target(), plan.staged(), plan.oldHash(), plan.newHash(), root, args);
        var bytes = new ByteArrayOutputStream(); original.write(bytes);
        assertEquals(original, UpdatePlan.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertThrows(IOException.class, () -> UpdatePlan.read(new ByteArrayInputStream(new byte[10])));
    }
    @Test void validatesModIdentityAndCorruptArchives() throws Exception {
        Path jar = root.resolve("update.jar");
        for (String id : List.of("spidicard", "another-mod")) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new ZipEntry("fabric.mod.json"));
                zip.write(("{\"id\":\"" + id + "\",\"version\":\"1.4.0\",\"environment\":\"client\",\"depends\":{\"minecraft\":\"~1.21.4\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry(); zip.putNextEntry(new ZipEntry("spidicard-update-agent.jar")); zip.closeEntry();
            }
            var release = new ReleaseManifest("1.4.0", "SpidiCard-1.21.4-1.4.0.jar", SafeInstall.hash(jar));
            if (id.equals("spidicard")) UpdateArtifact.validate(jar, release);
            else assertThrows(IOException.class, () -> UpdateArtifact.validate(jar, release));
        }
        Files.writeString(jar, "not a ZIP");
        assertThrows(IOException.class, () -> UpdateArtifact.validate(jar, new ReleaseManifest("1.4.0", "x", SafeInstall.hash(jar))));
    }
    @Test void prismFindsActualPortableInstanceWithSpaces() throws Exception {
        Path instance = root.resolve("Prism Portable/instances/Мой инстанс"); Files.createDirectories(instance);
        Files.writeString(instance.resolve("instance.cfg"), "name=Мой инстанс");
        for (String exe : List.of("/Applications/PrismLauncher 2.app/Contents/MacOS/prismlauncher", "C:/Program Files/Prism/prismlauncher.exe")) {
            for (String folder : List.of("minecraft", ".minecraft")) {
                var args = RestartCommand.prism(instance.resolve(folder), exe);
                assertEquals(List.of(exe, "--dir", root.resolve("Prism Portable").toAbsolutePath().toString(), "--launch", "Мой инстанс"), args);
            }
        }
        assertTrue(RestartCommand.prism(root, "/usr/bin/java").isEmpty());
    }
    @Test void directFabricReplayKeepsExactArgvAndRejectsWrappers() {
        var args = new String[]{"-Xmx4G", "-cp", "dir with spaces/game.jar", "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "Name with spaces"};
        var command = RestartCommand.direct("C:/Java 21/bin/javaw.exe", args);
        assertEquals(List.of(args), command.subList(1, command.size()));
        assertTrue(RestartCommand.direct("java", new String[]{"@temporary-args", "net.fabricmc.loader.impl.launch.knot.KnotClient"}).isEmpty());
        assertTrue(RestartCommand.direct("java", new String[]{"org.prismlauncher.EntryPoint"}).isEmpty());
        assertTrue(RestartCommand.direct("wrapper", args).isEmpty());
    }
    @Test void missingOsArgvUsesTokenizedFabricAndVmArgumentsWithoutSplittingValues() {
        String main = "net.fabricmc.loader.impl.launch.knot.KnotClient";
        var command = RestartCommand.snapshot("java", List.of("-Xmx4G", "-Dsome.path=folder with spaces"),
                "libraries/a.jar;libraries/b with spaces.jar", main + " deliberately unused string", new String[]{"--accessToken", "dummy-token", "--gameDir", "instance & folder"});
        assertEquals(List.of("java", "-Xmx4G", "-Dsome.path=folder with spaces", "-cp", "libraries/a.jar;libraries/b with spaces.jar",
                main, "--accessToken", "dummy-token", "--gameDir", "instance & folder"), command);
        assertTrue(RestartCommand.snapshot("java", List.of(), "some.jar", "org.prismlauncher.EntryPoint " + main, new String[0]).isEmpty());
    }
    @Test void windowsDoesNotReplayUnicodeThroughIncompatibleJavaCodepage() {
        String[] args = {"net.fabricmc.loader.impl.launch.knot.KnotClient", "--gameDir", "C:/Инстанс/игра"};
        assertTrue(RestartCommand.direct("C:/Java/bin/java.exe", args, "Windows 11", "windows-1252").isEmpty());
        assertFalse(RestartCommand.direct("C:/Java/bin/java.exe", args, "Windows 11", "windows-1251").isEmpty());
        assertFalse(RestartCommand.direct("/usr/bin/java", args, "Mac OS X", "UTF-8").isEmpty());
    }
    @Test void realHelperWaitsForOldJvmAndThenInstallsAndRestarts() throws Exception {
        var plan = fixture(); String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path fixtureJava = root.resolve("Fixture.java");
        Files.writeString(fixtureJava, "import java.nio.file.*; public class Fixture { public static void main(String[] a) throws Exception { if(a.length==0) System.in.read(); else { Files.writeString(Path.of(System.getProperty(\"probe.output\")),String.join(\"\\n\",a)); Files.writeString(Path.of(a[0]),a[1]); } } }");
        var compiler = new ProcessBuilder(java.replaceFirst("java(?:\\.exe)?$", System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac"), fixtureJava.toString()).start();
        assertTrue(compiler.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, compiler.exitValue());
        Process parent = new ProcessBuilder(java, "-cp", root.toString(), "Fixture").start();
        Path localHelper = plan.gameDir().resolve(".spidicard/agent.jar");
        Files.copy(Path.of("build/update-agent/spidicard-update-agent.jar"), localHelper);
        Process helper = new ProcessBuilder(java, "-jar", "agent.jar").directory(localHelper.getParent().toFile()).start();
        try {
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            Path marker = root.resolve(windows ? "restart & ok.txt" : "перезапуск & ok.txt");
            String payload = windows ? "Exact argv & $ '" : "Точные аргументы & $ '";
            var actual = new UpdatePlan(parent.pid(), plan.gameDir(), plan.target(), plan.staged(), plan.oldHash(), plan.newHash(), root,
                    List.of(java, "-Dprobe.output=" + root.resolve("restart-argv.txt"), "-cp", root.toString(), "Fixture", marker.toString(), payload));
            try (var out = helper.getOutputStream()) { actual.write(out); }
            var read = CompletableFuture.supplyAsync(() -> { try { return new BufferedReader(new InputStreamReader(helper.getInputStream())).readLine(); } catch (IOException e) { throw new CompletionException(e); } });
            assertEquals("READY", read.get(15, TimeUnit.SECONDS));
            assertEquals("old", Files.readString(plan.target())); assertTrue(helper.isAlive());
            parent.getOutputStream().close(); assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, helper.exitValue());
            assertEquals("new", Files.readString(plan.target()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(50);
            if (!Files.exists(marker)) {
                try (var files = Files.list(root)) {
                    throw new AssertionError("Restart output missing; argv=" + (Files.exists(root.resolve("restart-argv.txt"))
                            ? Files.readString(root.resolve("restart-argv.txt")) : "main not reached") + "; files=" + files.map(p -> p.getFileName().toString()).toList());
                }
            }
            assertEquals(payload, Files.readString(marker));
            assertFalse(Files.readString(plan.gameDir().resolve(".spidicard/update.log")).contains(payload));
        } finally { parent.destroyForcibly(); helper.destroyForcibly(); }
    }
}
