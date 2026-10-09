package net.spidicard;

import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.WeakHashMap;

/** Local presentation only. Font aliases keep vanilla glyphs and mark our gradients. */
public final class ChatTheme {
    public static final Identifier PREFIX = Identifier.of("spidicard", "prefix");
    public static final Identifier BODY = Identifier.of("spidicard", "body");
    public static final Identifier ERROR = Identifier.of("spidicard", "error");
    private static final Map<OrderedText, Boolean> OWNED = new WeakHashMap<>();
    private ChatTheme() {}

    public static MutableText message(String text) {
        return prefix().append(gradient(text, 0xDAF1FF, 0xDCD0FF, BODY, false));
    }
    public static MutableText error(String text) {
        return prefix().append(gradient("ОШИБКА  ", 0xFF719B, 0xFFBD8C, ERROR, true))
                .append(gradient(text, 0xFFB1C3, 0xFFE0BC, ERROR, false));
    }
    private static MutableText prefix() {
        return gradient("[SpidiCard]", 0xAA71FF, 0x5AE0FF, PREFIX, true).append(Text.literal(" "));
    }
    public static MutableText title(String text) {
        return gradient(text, 0xFF729E, 0xFFD08A, ERROR, true);
    }
    public static MutableText subtitle(String text) {
        return gradient(text, 0xE7F5FF, 0xDCD0FF, BODY, false);
    }
    public static MutableText gradient(String text, int start, int end, Identifier font, boolean bold) {
        MutableText result = Text.empty();
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            double position = points.length < 2 ? 0 : (double) i / (points.length - 1);
            result.append(Text.literal(new String(Character.toChars(points[i])))
                    .setStyle(Style.EMPTY.withColor(mix(start, end, position)).withFont(font).withBold(bold)));
        }
        return result;
    }

    /** Wrap only our text. Cached ownership leaves ordinary chat and other mods' text untouched. */
    public static OrderedText animate(OrderedText original) {
        boolean owned = OWNED.computeIfAbsent(original, text -> !text.accept((index, style, point) -> !themed(style)));
        if (!owned) return original;
        return animate(original, System.nanoTime());
    }
    public static OrderedText animate(OrderedText original, long nanos) {
        double phase = (nanos % 6_000_000_000L) * (2 * Math.PI / 6_000_000_000L);
        return visitor -> original.accept((index, style, point) -> {
            if (!themed(style) || style.getColor() == null) return visitor.accept(index, style, point);
            double strength = style.getFont().equals(PREFIX) ? 0.38 : style.getFont().equals(ERROR) ? 0.24 : 0.10;
            double glow = (Math.sin(phase - index * 0.30) + 1) * 0.5 * strength;
            Style animated = style.withColor(mix(style.getColor().getRgb(), 0xF1F5FF, glow))
                    .withFont(Style.DEFAULT_FONT_ID);
            return visitor.accept(index, animated, point);
        });
    }
    private static boolean themed(Style style) {
        Identifier font = style.getFont();
        return PREFIX.equals(font) || BODY.equals(font) || ERROR.equals(font);
    }
    private static int mix(int start, int end, double position) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = start >> shift & 255, b = end >> shift & 255;
            result |= (int) Math.round(a + (b - a) * position) << shift;
        }
        return result;
    }
}
