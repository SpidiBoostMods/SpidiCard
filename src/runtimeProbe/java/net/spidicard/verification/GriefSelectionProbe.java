package net.spidicard.verification;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.spidicard.FullRun;
import net.spidicard.SpidiCardClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Native route/parser checks; never packaged with the released mod. */
public final class GriefSelectionProbe {
    private record Case(String command, List<Integer> expected, int arrows, int extraRequests) {}
    private final List<Case> cases = List.of(
            new Case("spidicard full 3-5", List.of(3,4,5),0,1),
            new Case("spidicard full 3-5, 8-12, 53, 56",List.of(3,4,5,8,9,10,11,12,53,56),3,0),
            new Case("spidicart full 56,53, 53 - 54, 3-3",List.of(3,53,54,56),3,0));
    private int index = -1;
    private final long deadline = System.nanoTime() + 240_000_000_000L;
    private boolean done;
    private Process previous;
    private long readyCount;

    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(String name) throws Exception {
        var f=SpidiCardClient.class.getDeclaredField(name); f.setAccessible(true);
        return f.get(SpidiCardClient.INSTANCE);
    }
    private static FullRun full() throws Exception { return (FullRun)field("fullRun"); }
    private static Process viewer() throws Exception {
        Object helper=field("resultViewer"); var f=helper.getClass().getDeclaredField("previous"); f.setAccessible(true);
        return (Process)f.get(helper);
    }
    private static int command(MinecraftClient client,String text) throws Exception {
        return ClientCommandManager.getActiveDispatcher().execute(text,
                (FabricClientCommandSource)client.getNetworkHandler().getCommandSource());
    }
    private static long readyCount(Path game) throws Exception {
        Path log=game.resolve("logs/spidicard-viewer.log");
        return Files.exists(log)?Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count():0;
    }
    private void start(MinecraftClient client) throws Exception {
        ServerProbe.REQUESTS.clear(); ServerProbe.VISITED.clear(); ServerProbe.NAVIGATION.clear();
        if(command(client,cases.get(index).command())!=1)throw new AssertionError("Selection command rejected");
    }
    private void tick(MinecraftClient client) {
        if(done)return;
        Path game=client.runDirectory.toPath(),report=game.resolve("selection-results.txt");
        try {
            if(System.nanoTime()>deadline)throw new AssertionError("Selection verification timeout at case "+index);
            if(client.player==null||client.world==null||client.getNetworkHandler()==null)return;
            if(index<0) {
                var ready=SpidiCardClient.class.getDeclaredMethod("worldReady");ready.setAccessible(true);
                if(!(boolean)ready.invoke(SpidiCardClient.INSTANCE)||client.player.age<40)return;
                Files.deleteIfExists(game.resolve("runtime-failure.txt"));
                Files.writeString(report,"SpidiCard native selection verification\n");
                Files.writeString(game.resolve("spidicard.txt"),"PreviousSavedNick");
                for(String bad:List.of("0","57","3-5,57","3-5,8-6","3,","3,,5","3 5","3--5")) {
                    try {command(client,"spidicard full "+bad);throw new AssertionError("Invalid selection accepted: "+bad);}
                    catch(CommandSyntaxException expected){}
                }
                if(full()!=null||!ServerProbe.NAVIGATION.isEmpty()
                        ||!Files.readString(game.resolve("spidicard.txt")).equals("PreviousSavedNick"))
                    throw new AssertionError("Invalid selection had side effects");
                Files.writeString(report,"PASS: malformed selections rejected whole without requests or changed results\n",java.nio.file.StandardOpenOption.APPEND);
                command(client,"spidicard count 3");command(client,"spidicard delay 0");command(client,"spidicard timeout 5000");
                readyCount=readyCount(game); index=0;start(client);return;
            }
            if(full().active()) {
                if(viewer()!=previous)throw new AssertionError("Intermediate grief reopened viewer");
                return;
            }
            Process current=viewer();long count=readyCount(game);
            if(current==null||current==previous||!current.isAlive()||count<=readyCount)return;
            if(previous!=null&&previous.isAlive())throw new AssertionError("Old viewer still alive");
            var test=cases.get(index);
            var expected=test.expected().stream().map(n->String.format("CardG%02d - grief #%d",n,n)).toList();
            if(!ServerProbe.VISITED.equals(test.expected())||full().completedGriefs()!=test.expected().size()
                    ||!Files.readAllLines(game.resolve("spidicard.txt")).equals(expected))
                throw new AssertionError("Wrong selected route/result: "+ServerProbe.VISITED);
            if(ServerProbe.NAVIGATION.stream().filter("hub"::equals).count()!=expected.size()
                    ||ServerProbe.NAVIGATION.stream().filter("arrow:44"::equals).count()!=test.arrows()
                    ||ServerProbe.REQUESTS.size()!=expected.size()*3+test.extraRequests())
                throw new AssertionError("Wrong requests, hub or page count: "+ServerProbe.NAVIGATION);
            for(String name:ServerProbe.REQUESTS) {
                if(!name.matches("(?:Card|Low|Missing)G[0-9]{2}")
                        ||!test.expected().contains(Integer.parseInt(name.substring(name.length()-2))))
                    throw new AssertionError("Unselected player queried: "+name);
            }
            Files.writeString(game.resolve("selection-case-"+index+"-saved.txt"),Files.readString(game.resolve("spidicard.txt")));
            Files.writeString(report,"PASS: "+test.command()+" -> "+test.expected()+"; exact results, no unselected requests, viewer replaced\n",java.nio.file.StandardOpenOption.APPEND);
            previous=current;readyCount=count;
            if(++index<cases.size())start(client);
            else {Files.writeString(report,"ALL SELECTION CHECKS PASSED\n",java.nio.file.StandardOpenOption.APPEND);done=true;client.scheduleStop();}
        } catch(Throwable error) {
            error.printStackTrace();
            try{Files.writeString(game.resolve("runtime-failure.txt"),error.toString());}catch(Exception ignored){}
            done=true;client.scheduleStop();
        }
    }
}
