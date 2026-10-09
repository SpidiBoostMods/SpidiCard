package net.spidicard.verification;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.spidicard.ChatTheme;
import net.spidicard.FullRun;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Isolated native rendering, title and actual Fabric command execution verification. */
public final class ChatStyleProbe {
    private int step;
    private long changed;
    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private Process previous;
    private long readyCount;
    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(Object owner, String name) throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static Object mod(String name) throws Exception {return field(SpidiCardClient.INSTANCE,name);}
    private static void command(String text) {
        if(!ClientCommandInternals.executeCommand(text))throw new AssertionError("Mod command leaked to server: "+text);
    }
    private static void pass(Path game,String text) throws Exception {
        Files.writeString(game.resolve("chat-style-results.txt"),"PASS: "+text+"\n",java.nio.file.StandardOpenOption.APPEND);
    }
    private boolean viewerReady(Path game) throws Exception {
        Process current=(Process)field(mod("resultViewer"),"previous");
        Path log=game.resolve("logs/spidicard-viewer.log");
        long count=Files.exists(log)?Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count():0;
        if(current==null||current==previous||!current.isAlive()||count<=readyCount)return false;
        if(previous!=null&&previous.isAlive())throw new AssertionError("Previous viewer alive");
        previous=current;readyCount=count;return true;
    }
    private static List<Integer> colors(OrderedText text) {
        var result=new ArrayList<Integer>();
        text.accept((index,style,point)->{result.add(style.getColor()==null?0:style.getColor().getRgb());return true;});
        return result;
    }
    private static void image(MinecraftClient client,Path game,String name) throws Exception {
        try(var screenshot=ScreenshotRecorder.takeScreenshot(client.getFramebuffer())){screenshot.writeTo(game.resolve(name));}
    }
    private void tick(MinecraftClient client) {
        if(step==99)return;
        Path game=client.runDirectory.toPath();
        try {
            if(System.nanoTime()>deadline)throw new AssertionError("Chat style timeout at step "+step);
            if(client.player==null||client.world==null||client.getNetworkHandler()==null)return;
            long now=System.nanoTime();
            switch(step) {
                case 0 -> {
                    var ready=SpidiCardClient.class.getDeclaredMethod("worldReady");ready.setAccessible(true);
                    if(!(boolean)ready.invoke(SpidiCardClient.INSTANCE)||client.player.age<40)return;
                    MixinEnvironment.getCurrentEnvironment().audit();
                    Files.deleteIfExists(game.resolve("runtime-failure.txt"));
                    Files.writeString(game.resolve("chat-style-results.txt"),"SpidiCard native chat/theme verification\n");
                    Files.writeString(game.resolve("spidicard.txt"),"PreviousSavedNick");
                    var ordinary=Text.literal("Обычный чат").setStyle(Style.EMPTY.withColor(0x123456).withItalic(true)).asOrderedText();
                    if(ChatTheme.animate(ordinary)!=ordinary)throw new AssertionError("Ordinary text was changed");
                    var themed=ChatTheme.message("Ники: Nick1 Зелёная 🗝");
                    if(!themed.getString().equals("[SpidiCard] Ники: Nick1 Зелёная 🗝"))throw new AssertionError("Gradient changed Unicode/text");
                    if(colors(ChatTheme.animate(themed.asOrderedText(),0)).equals(colors(ChatTheme.animate(themed.asOrderedText(),2_000_000_000L))))
                        throw new AssertionError("Gradient does not animate");
                    if(client.textRenderer.getWidth(themed)!=client.textRenderer.getWidth(ChatTheme.animate(themed.asOrderedText(),0)))
                        throw new AssertionError("Animated font changes layout width");
                    if(SpidiCardClient.INSTANCE.handleLocalCommand("spidicard_other full")
                            ||SpidiCardClient.INSTANCE.handleLocalCommand("hub"))throw new AssertionError("Other commands intercepted");
                    command("spidicard count 3");command("spidicard delay 0");command("spidicard timeout 1000");
                    for(String bad:List.of("spidicard full 3-5,57","spidicard count 0","spidicard delay abc","spidicard unknown","spidicart full 8-3"))command(bad);
                    if((int)mod("minimumCount")!=3||mod("fullRun")!=null||!ServerProbe.NAVIGATION.isEmpty()
                            ||!Files.readString(game.resolve("spidicard.txt")).equals("PreviousSavedNick"))
                        throw new AssertionError("Invalid command changed state/results or sent packets");
                    pass(game,"gradients preserve Unicode/width, animate colors; ordinary styles and unrelated commands unchanged");
                    pass(game,"actual Fabric executor: invalid list/count/delay/unknown/alias errors are consumed locally without server requests");
                    client.inGameHud.getChatHud().clear(false);
                    command("spidicard");command("spidicard full 3-5,57");
                    client.setScreen(new ChatScreen(""));changed=now;step=1;
                }
                case 1 -> {
                    if(now-changed<700_000_000L)return;
                    Text title=(Text)field(client.inGameHud,"title");
                    if(title==null||!title.getString().equals("ОШИБКА СПИСКА"))throw new AssertionError("Error title absent or wrong: "+title);
                    if((int)field(client.inGameHud,"titleFadeInTicks")!=8||(int)field(client.inGameHud,"titleStayTicks")!=52
                            ||(int)field(client.inGameHud,"titleFadeOutTicks")!=18)throw new AssertionError("Wrong title fade");
                    var cache=ChatTheme.class.getDeclaredField("OWNED");cache.setAccessible(true);
                    if(!((Map<?,?>)cache.get(null)).containsValue(true))throw new AssertionError("Render mixin never drew themed text");
                    image(client,game,"chat-style-error-a.png");step=2;
                }
                case 2 -> {
                    if(now-changed<1_700_000_000L)return;
                    image(client,game,"chat-style-error-b.png");step=3;
                }
                case 3 -> {
                    if((int)field(client.inGameHud,"titleRemainTicks")!=0)return;
                    if(field(client.inGameHud,"title")!=null||field(client.inGameHud,"subtitle")!=null)throw new AssertionError("Title did not clear");
                    pass(game,"real animated chat/title rendered, screenshots exported, title fades in/out and clears automatically");
                    client.setScreen(null);client.getNetworkHandler().sendChatCommand("qa_small_tab");step=4;
                }
                case 4 -> {
                    if(client.getNetworkHandler().getListedPlayerListEntries().size()!=2)return;
                    command("spidicard start");step=5;
                }
                case 5 -> {
                    ScanSession scan=(ScanSession)mod("session");
                    if(scan.active()||!viewerReady(game))return;
                    if(!Files.readString(game.resolve("spidicard.txt")).equals("QAPlayer000"))throw new AssertionError("Start result changed");
                    pass(game,"actual styled start command still scans and saves exact nick, opens result viewer");
                    ServerProbe.REQUESTS.clear();ServerProbe.VISITED.clear();ServerProbe.NAVIGATION.clear();
                    command("spidicart full 56-56");step=6;
                }
                case 6 -> {
                    FullRun full=(FullRun)mod("fullRun");if(full.active()||!viewerReady(game))return;
                    if(!ServerProbe.VISITED.equals(List.of(56))||!Files.readString(game.resolve("spidicard.txt")).strip().equals("CardG56 - grief #56"))
                        throw new AssertionError("Styled alias/full range changed results");
                    pass(game,"actual Fabric alias full range completes grief 56, saves exact result, replaces viewer after retry");
                    Files.writeString(game.resolve("chat-style-results.txt"),"ALL CHAT STYLE CHECKS PASSED\n",java.nio.file.StandardOpenOption.APPEND);
                    step=99;client.scheduleStop();
                }
            }
        } catch(Throwable error) {
            error.printStackTrace();try{Files.writeString(game.resolve("runtime-failure.txt"),error.toString());}catch(Exception ignored){}
            step=99;client.scheduleStop();
        }
    }
}
