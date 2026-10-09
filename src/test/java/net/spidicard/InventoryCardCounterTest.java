package net.spidicard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCardCounterTest {
    @Test void completeSnapshotPreservesFormattingNamesAndStackQuantities() {
        var slots = List.of(
                stack("§9§lСиняя Ключ-Карта", 2),
                stack("Красная Ключ-Карта", 3),
                stack("ЗЕЛЁНАЯ\u00a0Ключ – Карта", 4),
                stack("Зеленая Ключ‑Карта", 5),
                stack("Синяя\u200b Ключ-Карта", 6),
                stack("Синяя Ключ-Карта поддельная", 64),
                stack("Жёлтая Ключ-Карта", 64),
                new CardNames.Stack("Зелёная Ключ-Карта", 64, true),
                stack(null, 0), stack(null, -1));
        assertEquals(20, new InventoryCardCounter(slots).total());
        assertEquals(CardNames.count(slots), new InventoryCardCounter(slots).total());
    }

    @Test void updatesReplacePriorCountsAcrossQuantityColorAndRemoval() {
        var counter = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 2), stack("", 0)));
        counter.update(1, stack("Красная Ключ-Карта", 3));
        assertEquals(5, counter.total());
        counter.update(1, stack("Красная Ключ-Карта", 3));
        assertEquals(5, counter.total());
        counter.update(0, stack("Зелёная Ключ-Карта", 10));
        assertEquals(13, counter.total());
        counter.update(0, stack("Зелёная Ключ-Карта", 1));
        assertEquals(4, counter.total());
        counter.update(1, stack("Обычная бирка", 64));
        assertEquals(1, counter.total());
        counter.update(0, stack(null, 0));
        assertEquals(0, counter.total());
    }

    @Test void ownSlotsStayExcludedAndIncomingOwnStacksAreIgnored() {
        var counter = new InventoryCardCounter(List.of(
                new CardNames.Stack("Зелёная Ключ-Карта", 64, true),
                stack("Красная Ключ-Карта", 2)));
        assertEquals(2, counter.total());
        counter.update(0, stack("Синяя Ключ-Карта", 64));
        assertEquals(2, counter.total());
        counter.update(1, new CardNames.Stack("Красная Ключ-Карта", 64, true));
        assertEquals(0, counter.total());
        counter.update(1, stack("Синяя Ключ-Карта", 3));
        assertEquals(3, counter.total());
    }

    @Test void newCompleteSnapshotRebuildsCountsAndDoesNotRetainSourceList() {
        var source = new ArrayList<>(List.of(stack("Синяя Ключ-Карта", 3), stack("Красная Ключ-Карта", 2)));
        var first = new InventoryCardCounter(source);
        source.set(0, stack("", 0));
        source.set(1, stack("Зелёная Ключ-Карта", 1));
        var replacement = new InventoryCardCounter(source);
        assertEquals(5, first.total());
        assertEquals(1, replacement.total());
        assertEquals(0, new InventoryCardCounter(List.of()).total());
    }

    @Test void badIndexesAndInvalidStacksLeaveExistingTotalIntact() {
        var counter = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 3)));
        for (int slot : List.of(-1, 1, Integer.MAX_VALUE)) {
            assertThrows(IndexOutOfBoundsException.class, () -> counter.update(slot, stack("Красная Ключ-Карта", 2)));
            assertEquals(3, counter.total());
        }
        assertThrows(NullPointerException.class, () -> counter.update(0, null));
        assertThrows(NullPointerException.class, () -> counter.update(0, stack(null, 1)));
        assertEquals(3, counter.total());
        counter.update(0, stack("Синяя Ключ-Карта", -1));
        assertEquals(0, counter.total());
    }

    @Test void overflowMatchesCompleteCountAndFailedUpdateDoesNotCorruptContributions() {
        var overflow = List.of(stack("Синяя Ключ-Карта", Integer.MAX_VALUE), stack("Красная Ключ-Карта", 1));
        assertThrows(ArithmeticException.class, () -> CardNames.count(overflow));
        assertThrows(ArithmeticException.class, () -> new InventoryCardCounter(overflow));
        var counter = new InventoryCardCounter(List.of(
                stack("Синяя Ключ-Карта", Integer.MAX_VALUE - 3), stack("Красная Ключ-Карта", 3)));
        assertEquals(Integer.MAX_VALUE, counter.total());
        assertThrows(ArithmeticException.class,
                () -> counter.update(0, stack("Синяя Ключ-Карта", Integer.MAX_VALUE)));
        assertEquals(Integer.MAX_VALUE, counter.total());
        counter.update(1, stack("", 0));
        assertEquals(Integer.MAX_VALUE - 3, counter.total());
        counter.update(0, stack("Синяя Ключ-Карта", Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, counter.total());
    }

    @Test void nameCacheStaysBoundedAndEvictionDoesNotChangeClassification() {
        var counter = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 3)));
        for (int name = 0; name < 1000; name++) {
            counter.update(0, stack("Обычная бирка " + name, 64));
            assertEquals(0, counter.total());
            assertTrue(counter.cachedNameCount() <= 256);
        }
        counter.update(0, stack("Синяя Ключ-Карта", 4));
        assertEquals(4, counter.total());
        counter.update(0, stack("Синяя Ключ-Карта поддельная", 64));
        assertEquals(0, counter.total());
        counter.update(0, stack("§x§0§0§F§F§0§0ЗЕЛЁНАЯ\u00a0Ключ – Карта", 2));
        assertEquals(2, counter.total());
        assertTrue(counter.cachedNameCount() <= 256);
    }

    @Test void variedIncrementalUpdatesEqualFreshFullCount() {
        var slots = new ArrayList<CardNames.Stack>();
        for (int slot = 0; slot < 72; slot++) slots.add(new CardNames.Stack("", 0, slot >= 36));
        var counter = new InventoryCardCounter(slots);
        var random = new Random(731029);
        var names = List.of("Синяя Ключ-Карта", "Красная Ключ-Карта", "Зелёная Ключ-Карта",
                "§9§lСиняя Ключ-Карта", "Зеленая Ключ‑Карта", "Синяя Ключ-Карта поддельная", "Камень");
        for (int update = 0; update < 2000; update++) {
            int slot = random.nextInt(slots.size());
            var stack = new CardNames.Stack(names.get(random.nextInt(names.size())), random.nextInt(66) - 1, slot >= 36);
            slots.set(slot, stack);
            counter.update(slot, stack);
            assertEquals(CardNames.count(slots), counter.total(), "update " + update);
        }
    }

    @Test void sharedNameCacheKeepsEachInventoryCountsAndOwnershipIndependent() {
        var cache = new InventoryCardCounter.NameCache();
        var first = new InventoryCardCounter(List.of(
                stack("Синяя Ключ-Карта", 2),
                new CardNames.Stack("Красная Ключ-Карта", 64, true)), cache);
        var second = new InventoryCardCounter(List.of(
                new CardNames.Stack("Синяя Ключ-Карта", 64, true),
                stack("Красная Ключ-Карта", 3),
                stack("Обычная бирка", 64)), cache);
        assertEquals(2, first.total());
        assertEquals(3, second.total());
        assertEquals(3, first.cachedNameCount());
        assertEquals(first.cachedNameCount(), second.cachedNameCount());

        first.update(0, stack("Синяя Ключ-Карта", 7));
        first.update(1, stack("Красная Ключ-Карта", 64));
        assertEquals(7, first.total());
        assertEquals(3, second.total());
        second.update(0, stack("Синяя Ключ-Карта", 64));
        second.update(1, stack("Красная Ключ-Карта", 1));
        assertEquals(1, second.total());
        assertEquals(7, first.total());

        var replacement = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 4)), cache);
        assertEquals(4, replacement.total());
        assertEquals(7, first.total());
        assertEquals(1, second.total());
        assertEquals(3, replacement.cachedNameCount());
        assertThrows(NullPointerException.class, () -> new InventoryCardCounter(List.of(), null));
    }

    @Test void sharedCacheEvictionBoundaryDoesNotAffectOtherInventoryTotals() {
        var cache = new InventoryCardCounter.NameCache();
        var first = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 3)), cache);
        var second = new InventoryCardCounter(List.of(stack("", 0)), cache);
        for (int name = 0; name < 255; name++)
            second.update(0, stack("Обычная бирка " + name, 1));
        assertEquals(256, first.cachedNameCount());
        assertEquals(256, second.cachedNameCount());
        assertEquals(3, first.total());
        assertEquals(0, second.total());

        second.update(0, stack("Ещё одна бирка", 1));
        assertEquals(256, first.cachedNameCount());
        assertEquals(3, first.total());
        var third = new InventoryCardCounter(List.of(stack("Синяя Ключ-Карта", 5)), cache);
        assertEquals(256, third.cachedNameCount());
        assertEquals(5, third.total());
        third.update(0, stack("Синяя Ключ-Карта поддельная", 64));
        assertEquals(0, third.total());
        assertEquals(3, first.total());
        assertEquals(0, second.total());
        assertEquals(256, first.cachedNameCount());
    }

    private static CardNames.Stack stack(String name, int count) {
        return new CardNames.Stack(name, count, false);
    }
}
