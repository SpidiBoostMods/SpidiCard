package net.spidicard.update;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.spidicard.ChatTheme;

final class UpdateScreen extends Screen {
    private final String version;
    UpdateScreen(String version, MinecraftClient game) {
        super(Text.literal("spidiboost.SpidiCard")); this.version = version;
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, width, height, 0xff0e1020, 0xff171b35);
        context.fillGradient(width / 2 - 130, height / 2 - 40, width / 2 + 130, height / 2 - 38, 0xffab70ff, 0xff58d9f7);
        context.drawCenteredTextWithShadow(textRenderer, ChatTheme.title("SpidiCard " + version), width / 2, height / 2 - 22, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Обновление проверено. Перезапускаем Minecraft…"), width / 2, height / 2 + 4, 0xc3d2ee);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Результаты проверки сохранены"), width / 2, height / 2 + 23, 0x8798bf);
    }
}
