package net.spidicard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SpidiCardTest {
    @TempDir Path directory;

    @Test void mixedColorsStackCountsAndOwnInventory() {
        assertEquals(3, CardNames.count(List.of(
                new CardNames.Stack("§9§lСиняя Ключ-Карта", 2, false),
                new CardNames.Stack("Красная Ключ-Карта", 1, false),
                new CardNames.Stack("Зелёная Ключ-Карта", 64, true),
                new CardNames.Stack("Обычная бирка", 64, false))));
        assertTrue(CardNames.isCard("§x§0§0§F§F§0§0ЗЕЛЁНАЯ\u00a0Ключ – Карта"));
        assertTrue(CardNames.isCard("Зеленая Ключ‑Карта"));
        assertTrue(CardNames.isCard("Синяя\u200b Ключ-Карта"));
        assertFalse(CardNames.isCard("Синяя Ключ-Карта поддельная"));
        assertFalse(CardNames.isCard("Жёлтая Ключ-Карта"));
        assertFalse(CardNames.isCard("Ключ-Карта"));
    }

    @Test void scans300PlayersExactlyOnceAndRewritesOneLine() throws Exception {
        var names = IntStream.range(0, 300).mapToObj(i -> "Player" + i).toList();
        var listener = new Probe();
        var scan = new ScanSession(names, 3, 100, 5000, 0, listener);
        long clock = 0;
        for (int i = 0; i < names.size(); i++) {
            scan.tick(clock);
            assertEquals(names.get(i), listener.requests.get(i));
            assertEquals(ScanSession.Phase.WAIT_OPEN, scan.phase());
            scan.opened(i + 1, "Инвентарь " + names.get(i), clock + 10);
            scan.contents(i + 1, i % 2 == 0 ? 3 : 2, clock + 20);
            scan.tick(clock + 69);
            assertEquals(i, scan.processed());
            scan.tick(clock + 70);
            assertEquals(i + 1, scan.processed());
            clock += 170;
        }
        scan.tick(clock);
        assertFalse(scan.active());
        assertTrue(listener.complete);
        assertEquals(300, scan.checked());
        assertEquals(300, listener.closed.size());
        assertEquals(150, scan.matches().size());
        Path output = directory.resolve("spidicard.txt");
        ResultsFile.write(output, List.of("OldNick"));
        ResultsFile.write(output, scan.matches());
        assertEquals(String.join(" ", scan.matches()), Files.readString(output));
        assertFalse(Files.readString(output).contains("OldNick"));
        assertFalse(Files.readString(output).contains("\n"));
        ResultsFile.write(output, List.of());
        assertEquals("", Files.readString(output));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void oldSyncAndIncrementalUpdatesCannotFinishBeforeFullContents() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Bob"), 3, 0, 5000, 0, listener);
        scan.tick(0);
        scan.contents(9, 64, 1); // Stale full packet before an open.
        scan.opened(10, "Inventory of Alice", 2);
        scan.slotUpdated(10, 64, 3); // Not a full inventory yet.
        scan.contents(9, 64, 4); // Stale previous inventory.
        scan.tick(100);
        assertEquals(0, scan.processed());
        scan.contents(10, 2, 101);
        scan.slotUpdated(10, 3, 140);
        scan.tick(189);
        assertEquals(0, scan.processed());
        scan.tick(190);
        assertEquals(List.of("Alice"), scan.matches());
        assertEquals(List.of("Alice", "Bob"), listener.requests);
        scan.contents(10, 100, 191); // Late packet must not count for Bob.
        scan.opened(11, "Inventory of Bob", 192);
        scan.contents(11, 0, 193);
        scan.tick(243);
        assertEquals(List.of("Alice"), scan.matches());
        assertTrue(listener.complete);
    }

    @Test void timeoutStopsWithoutSendingNextOrCountingEmptyInventory() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Bob"), 1, 0, 500, 0, listener);
        scan.tick(0);
        scan.opened(4, "Inventory", 100);
        scan.tick(599);
        assertTrue(scan.active());
        scan.tick(600);
        assertFalse(scan.active());
        assertFalse(listener.complete);
        assertEquals(List.of("Alice"), listener.requests);
        assertEquals(0, scan.checked());
        scan.contents(4, 10, 601);
        assertTrue(scan.matches().isEmpty());
    }

    @Test void mismatchedTitleStopsBeforeCountingOtherPlayer() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Bob"), 1, 100, 5000, 0, listener);
        scan.tick(0);
        scan.opened(1, "Inventory of Bob", 1);
        scan.contents(1, 99, 2);
        scan.tick(1000);
        assertFalse(scan.active());
        assertEquals(0, scan.checked());
        assertTrue(scan.matches().isEmpty());
    }

    @Test void unavailablePlayersSkippedAndDuplicateTabNamesRemoved() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Alice", "Bob"), 1, 0, 5000, 0, listener);
        scan.tick(0);
        scan.unavailable(1);
        scan.tick(750);
        assertEquals(List.of("Alice"), listener.requests);
        scan.tick(751);
        scan.opened(2, "Inventory", 752);
        scan.contents(2, 1, 753);
        scan.tick(803);
        assertEquals(2, scan.total());
        assertEquals(1, scan.checked());
        assertEquals(1, scan.skipped());
        assertEquals(List.of("Bob"), scan.matches());
        assertTrue(listener.complete);
    }

    @Test void userStopClosesOnlyOwnedWindowAndFinishesOnce() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice"), 1, 100, 5000, 0, listener);
        scan.tick(0);
        scan.opened(7, "Inventory", 1);
        scan.contents(7, 5, 2);
        scan.stop("User stop");
        scan.stop("Again");
        assertEquals(List.of(7), listener.closed);
        assertEquals(1, listener.finishCalls);
        assertTrue(scan.matches().isEmpty());
        assertFalse(scan.owns(7));
    }

    @Test void delayedOpenOrContentsCannotBeatTimeoutBetweenClientTicks() {
        var openProbe = new Probe();
        var opening = new ScanSession(List.of("Alice", "Bob"), 1, 0, 500, 0, openProbe);
        opening.tick(0);
        // No tick has run since the command. Late callback still must fail, rather than reset the deadline.
        opening.opened(7, "Inventory of Alice", 501);
        opening.contents(7, 10, 502);
        assertFalse(opening.active());
        assertEquals(ScanSession.Failure.TIMEOUT, opening.failure());
        assertEquals(List.of(7), openProbe.closed);
        assertTrue(opening.matches().isEmpty());
        assertEquals(List.of("Alice"), openProbe.requests);

        var contentProbe = new Probe();
        var contents = new ScanSession(List.of("Alice"), 1, 0, 500, 0, contentProbe);
        contents.tick(0); contents.opened(8, "Inventory", 100);
        contents.contents(8, 10, 601);
        assertFalse(contents.active());
        assertEquals(ScanSession.Failure.TIMEOUT, contents.failure());
        assertEquals(List.of(8), contentProbe.closed);
        assertTrue(contents.matches().isEmpty());
    }

    @Test void frequentFrameAndTickPumpsKeepQuietPeriodAndLatestContents() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Bob"), 3, 0, 5000, 0, listener);
        scan.tick(0); scan.opened(1, "Inventory of Alice", 1); scan.contents(1, 0, 2);
        for (long now = 3; now <= 190; now++) {
            // A plugin fills an initially empty inventory, including a repeated full packet.
            if (now == 45) scan.slotUpdated(1, 1, now);
            if (now == 90) scan.contents(1, 2, now);
            if (now == 135) scan.slotUpdated(1, 3, now);
            if (now == 180) scan.slotUpdated(1, 3, now); // Non-card changes still renew the guard.
            scan.tick(now); scan.tick(now); // Frame and game tick at the same timestamp.
            assertEquals(0, scan.processed());
            assertEquals(List.of("Alice"), listener.requests);
        }
        scan.tick(229); assertEquals(0, scan.processed());
        scan.tick(230);
        assertEquals(List.of("Alice", "Bob"), listener.requests);
        assertEquals(List.of("Alice"), scan.matches());
        scan.contents(1, 64, 231); scan.slotUpdated(1, 64, 232); // Late previous inventory.
        scan.opened(2, "Inventory of Bob", 233); scan.contents(2, 2, 234);
        scan.tick(284); scan.tick(284); scan.tick(5000);
        assertEquals(List.of("Alice"), scan.matches());
        assertEquals(List.of(1, 2), listener.closed);
        assertEquals(1, listener.finishCalls);
        assertTrue(listener.complete);
    }

    @Test void frequentPumpsRespectDelayAndNeverBurstAfterLongFrame() {
        var listener = new Probe();
        var scan = new ScanSession(List.of("Alice", "Bob", "Carol"), 1, 123, 5000, 0, listener);
        scan.tick(0); scan.opened(1, "Inventory", 1); scan.contents(1, 1, 2);
        scan.tick(52);
        for (long now = 53; now < 175; now++) { scan.tick(now); scan.tick(now); }
        assertEquals(List.of("Alice"), listener.requests);
        scan.tick(175); scan.tick(175);
        assertEquals(List.of("Alice", "Bob"), listener.requests);
        scan.opened(2, "Inventory", 180); scan.contents(2, 1, 181);
        scan.tick(4000); scan.tick(4000); // A freeze completes only the current target.
        assertEquals(List.of("Alice", "Bob"), listener.requests);
        scan.tick(4122); assertEquals(2, listener.requests.size());
        scan.tick(4123); scan.tick(4123);
        assertEquals(List.of("Alice", "Bob", "Carol"), listener.requests);
        scan.stop(ScanSession.Failure.USER_STOP, "Stop");
        scan.tick(99999); assertEquals(1, listener.finishCalls);
    }

    private static final class Probe implements ScanSession.Listener {
        final List<String> requests = new ArrayList<>();
        final List<Integer> closed = new ArrayList<>();
        boolean complete;
        int finishCalls;
        public void request(String name) { requests.add(name); }
        public void close(int syncId) { closed.add(syncId); }
        public void progress() {}
        public void finish(boolean complete, String reason) { this.complete = complete; finishCalls++; }
    }
}
