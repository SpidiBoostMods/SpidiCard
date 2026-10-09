package net.spidicard;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class CardNames {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern HYPHENS = Pattern.compile("[‐‑‒–—−]");
    private static final Pattern HYPHEN_SPACES = Pattern.compile(" *- *");
    private static final Set<String> NAMES = Set.of(
            "синяя ключ-карта", "красная ключ-карта", "зеленая ключ-карта");

    private CardNames() {}

    public static String normalize(String name) {
        String value = Normalizer.normalize(name, Normalizer.Form.NFKC);
        value = FORMATTING.matcher(value).replaceAll("");
        value = INVISIBLE.matcher(value).replaceAll("");
        value = HYPHENS.matcher(value.toLowerCase(Locale.ROOT).replace('ё', 'е')).replaceAll("-");
        value = SPACES.matcher(value).replaceAll(" ").trim();
        return HYPHEN_SPACES.matcher(value).replaceAll("-");
    }

    public static boolean isCard(String name) {
        return NAMES.contains(normalize(name));
    }

    public record Stack(String name, int count, boolean ownInventory) {}

    public static int count(Iterable<Stack> stacks) {
        int result = 0;
        for (Stack stack : stacks) {
            if (!stack.ownInventory() && stack.count() > 0 && isCard(stack.name())) {
                result = Math.addExact(result, stack.count());
            }
        }
        return result;
    }
}
