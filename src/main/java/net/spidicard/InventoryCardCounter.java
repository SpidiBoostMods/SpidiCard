package net.spidicard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Counts immutable slot snapshots; all updates belong on the client thread. */
public final class InventoryCardCounter {
    private static final int NAME_CACHE_LIMIT = 256;

    /** Reuse one cache throughout a scan; quantities and ownership remain per inventory. */
    public static final class NameCache {
        private final Map<String, Boolean> names = new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > NAME_CACHE_LIMIT;
            }
        };

        public NameCache() {}

        private boolean isCard(String name) {
            Boolean card = names.get(name);
            if (card == null) {
                card = CardNames.isCard(name);
                names.put(name, card);
            }
            return card;
        }
    }

    private final int[] contributions;
    private final boolean[] ownSlots;
    private final NameCache nameCache;
    private int total;

    public InventoryCardCounter(List<CardNames.Stack> slots) {
        this(slots, new NameCache());
    }

    public InventoryCardCounter(List<CardNames.Stack> slots, NameCache nameCache) {
        Objects.requireNonNull(slots, "slots");
        this.nameCache = Objects.requireNonNull(nameCache, "nameCache");
        contributions = new int[slots.size()];
        ownSlots = new boolean[slots.size()];
        for (int slot = 0; slot < slots.size(); slot++) {
            CardNames.Stack stack = Objects.requireNonNull(slots.get(slot), "stack");
            ownSlots[slot] = stack.ownInventory();
            int contribution = contribution(stack, ownSlots[slot]);
            total = Math.addExact(total, contribution);
            contributions[slot] = contribution;
        }
    }

    /** Replaces one slot's contribution; a failed update leaves the total intact. */
    public void update(int slot, CardNames.Stack stack) {
        Objects.checkIndex(slot, contributions.length);
        Objects.requireNonNull(stack, "stack");
        int contribution = contribution(stack, ownSlots[slot]);
        int nextTotal = Math.addExact(total - contributions[slot], contribution);
        contributions[slot] = contribution;
        total = nextTotal;
    }

    public int total() {
        return total;
    }

    private int contribution(CardNames.Stack stack, boolean ownSlot) {
        if (ownSlot || stack.ownInventory() || stack.count() <= 0) return 0;
        return nameCache.isCard(stack.name()) ? stack.count() : 0;
    }

    // Package access permits checking the memory bound without exposing the map.
    int cachedNameCount() {
        return nameCache.names.size();
    }
}
