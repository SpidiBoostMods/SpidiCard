package net.spidicard.update;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;

final class UpdateToast implements Toast {
    private final String title, detail;
    private Visibility visibility = Visibility.SHOW;
    UpdateToast(String title, String detail) { this.title = title; this.detail = detail; }
    @Override public Visibility getVisibility() { return visibility; }
    @Override public void update(ToastManager manager, long time) { visibility = time > 10000 ? Visibility.HIDE : Visibility.SHOW; }
    @Override public void draw(DrawContext context, net.minecraft.client.font.TextRenderer font, long time) {
        context.fillGradient(0, 0, getWidth(), getHeight(), 0xff282044, 0xff153744);
        context.drawText(font, Text.literal("SpidiCard: " + title), 8, 7, 0xcfafff, false);
        context.drawText(font, Text.literal(detail), 8, 21, 0xa8e7f5, false);
    }
    @Override public int getWidth() { return 300; }
}
