package net.spidicard.verification;
import net.spidicard.shared.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Public release validation through the same downloader, identity checks and batch installer. */
public final class FamilyReleaseProbe {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);
        Path game=Files.createTempDirectory(output,"Инстанс с пробелами-"),mods=game.resolve("mods"),dir=game.resolve(".spidiboost-updates");Files.createDirectories(mods);Files.createDirectories(dir);
        Files.writeString(mods.resolve("AdminTools.jar"),"protected");Files.writeString(game.resolve("config.txt"),"keep settings");var report=new ArrayList<String>();var changes=new ArrayList<BatchPlan.Change>();var http=new GitHubDownload();
        Map<String,String> expected=Map.of("spidicard","1.5.0","spidiban","1.1.0","sixubaafp","1.1.0","spidiboostlittlepet","1.2.0");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var pending=new ArrayList<Future<Map.Entry<Release,Path>>>();
            for(var mod:ModCatalog.ALL)pending.add(pool.submit(()->{var r=Release.parse(mod,new String(http.get(mod.latest(),16384),StandardCharsets.UTF_8));if(!r.version().equals(expected.get(mod.id())))throw new AssertionError("Unexpected public latest version "+mod.id()+": "+r.version());Path downloaded=output.resolve(r.artifact());Files.write(downloaded,http.get(r.download(),32*1024*1024));Artifact.validate(downloaded,r);return Map.entry(r,downloaded);}));
            for(var future:pending){var e=future.get();var r=e.getKey();Path target=mods.resolve(r.mod().name()+"-old.jar"),staged=dir.resolve(r.artifact());Files.writeString(target,"Old installed "+r.mod().id());Files.copy(e.getValue(),staged);changes.add(new BatchPlan.Change(target,mods.resolve(r.artifact()),staged,BatchInstall.hash(target),r.sha256()));report.add("PASS public "+r.mod().name()+" "+r.version()+" identity, author, filename, embedded helper and SHA-256 "+r.sha256());}
        }finally{pool.shutdown();}
        Path hist=mods.resolve("SpidiBan-Shist-Addon.jar");try(var zip=new ZipOutputStream(Files.newOutputStream(hist))){zip.putNextEntry(new ZipEntry("fabric.mod.json"));zip.write("{\"id\":\"spidiban_shist\",\"version\":\"1.2.1\"}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        var removal=new BatchPlan.Removal(hist,BatchInstall.hash(hist));var plan=new BatchPlan(ProcessHandle.current().pid(),game,changes,List.of(removal),game,List.of(),false);BatchInstall.install(plan);
        for(var c:changes){if(Files.exists(c.target())||!BatchInstall.hash(c.destination()).equals(c.newHash()))throw new AssertionError("Batch installation mismatch");if(!BatchInstall.hash(dir.resolve("backups/"+c.oldHash()+".jar.bak")).equals(c.oldHash()))throw new AssertionError("Backup mismatch");}
        if(Files.exists(hist)||!Files.readString(game.resolve("config.txt")).equals("keep settings")||!Files.readString(mods.resolve("AdminTools.jar")).equals("protected"))throw new AssertionError("Migration or unrelated files changed");
        if(!BatchInstall.hash(dir.resolve("backups/"+removal.hash()+".jar.bak")).equals(removal.hash()))throw new AssertionError("Hist backup mismatch");
        report.add("PASS batch installation of all four actual public JARs, canonical filenames, backups, Hist migration and unchanged AdminTools/config");
        Files.writeString(output.resolve("public-family-results.txt"),String.join("\n",report)+"\nALL PUBLIC RELEASE CHECKS PASSED\n");for(var s:report)System.out.println(s);
    }
}
