package net.spidicard.verification;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.spidicard.shared.*;
import java.nio.file.Files;
import java.util.*;

/** Isolated native Minecraft check with the four real mod JARs loaded together. */
final class FamilyProbe {
    private int ticks,step;private final List<String> evidence=new ArrayList<>();
    void register(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    void tick(MinecraftClient c) {
        if(step==99)return;
        try {
            if(++ticks>2400)throw new AssertionError("Family runtime timeout");
            if(step==0&&c.world!=null&&c.player!=null&&c.player.age>40&&c.currentScreen==null) {
                var loader=FabricLoader.getInstance();
                for(var m:ModCatalog.ALL){var mod=loader.getModContainer(m.id()).orElseThrow();require(mod.getMetadata().getName().equals("spidiboost."+m.name()),"Mod Menu name "+m.id());require(mod.getMetadata().getAuthors().size()==1&&mod.getMetadata().getAuthors().iterator().next().getName().equals("SpidiBoost"),"author");String icon=mod.getMetadata().getIconPath(64).orElseThrow();require(mod.findPath(icon).isPresent(),"missing icon "+m.id());}
                require(!loader.isModLoaded("spidiban_shist"),"obsolete addon loaded");evidence.add("PASS four real mod identities, versions, authors and icons; standalone Hist absent");
                require(loader.getObjectShare().get(SharedUpdater.OWNER) instanceof Runnable,"shared owner missing");require(!SharedUpdater.claim(loader.getObjectShare(),()->{}),"duplicate owner accepted");evidence.add("PASS one coordinator elected across four relocated copies");
                var dispatcher=ClientCommandManager.getActiveDispatcher();require(dispatcher!=null,"no client dispatcher");
                for(String alias:List.of("sb","spidiban","spidiboost")){
                    var root=dispatcher.getRoot().getChild(alias);require(root!=null,"missing alias "+alias);
                    for(String action:List.of("accept","cheats","cancel","delay","shist"))require(root.getChild(action)!=null,"missing merged command "+action);
                    for(String command:List.of(alias+" shist Smoke ban",alias+" shist Smoke mute 100",alias+" shist retry",alias+" shist speed 50",alias+" shist status")) {var parsed=dispatcher.parse(command,null);require(!parsed.getReader().canRead()&&parsed.getContext().getCommand()!=null,"incomplete command "+command);}
                }
                require(dispatcher.getRoot().getChild("6ubaafp")!=null,"FP commands missing");require(dispatcher.getRoot().getChild("spidicard")!=null,"Card commands missing");evidence.add("PASS merged SpidiBan + full Hist command tree and FP/Card commands in native client");
                var rows=ModCatalog.ALL.stream().map(m->new UpdateRow(m.id(),m.name(),loader.getModContainer(m.id()).orElseThrow().getMetadata().getVersion().getFriendlyString(),"Обновление проверено ✓")).toList();
                c.setScreen(new UpdateScreen(()->rows,"Обновления проверены. Перезапускаем Minecraft…","Результаты проверки сохранены",true,null));require(!c.currentScreen.shouldCloseOnEsc(),"restart screen dismissible");step=1;ticks=0;
            }else if(step==1&&ticks>30){try(var image=ScreenshotRecorder.takeScreenshot(c.getFramebuffer())){image.writeTo(c.runDirectory.toPath().resolve("family-update-screen.png"));}evidence.add("PASS actual four-row update screen rendered; screenshot captured");Files.writeString(c.runDirectory.toPath().resolve("family-results.txt"),String.join("\n",evidence)+"\nALL FAMILY CLIENT CHECKS PASSED\n");step=99;c.scheduleStop();}
        }catch(Throwable e){e.printStackTrace();try{Files.writeString(c.runDirectory.toPath().resolve("family-results.txt"),"FAIL "+e);}catch(Exception ignored){}step=99;c.scheduleStop();}
    }
    private static void require(boolean b,String detail){if(!b)throw new AssertionError(detail);}
}
