package PACKAGE;
import net.fabricmc.api.ClientModInitializer;
public final class SharedBootstrap implements ClientModInitializer {
    @Override public void onInitializeClient(){SharedUpdater.initialize();}
}
