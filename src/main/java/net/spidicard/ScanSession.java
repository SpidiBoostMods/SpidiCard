package net.spidicard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/** All calls, including network callbacks, must run on the Minecraft client thread. */
public final class ScanSession {
    public enum Phase { READY, WAIT_OPEN, WAIT_CONTENTS, SETTLING, FINISHED }
    public enum Failure { NONE, TIMEOUT, USER_STOP, DISCONNECTED, OTHER }

    public interface Listener {
        void request(String name);
        void close(int syncId);
        void progress();
        void finish(boolean complete, String reason);
    }

    private static final Pattern NAME_TOKEN = Pattern.compile("[a-zA-Z0-9_]{1,16}");
    private final List<String> names;
    private final Set<String> lowerNames;
    private final List<String> matches = new ArrayList<>();
    private final Listener listener;
    private final int threshold;
    private final int delayMs;
    private final int timeoutMs;
    private final Predicate<String> excluded;
    private final ScanTimings timings;
    private Failure failure = Failure.NONE;
    private Phase phase = Phase.READY;
    private int index;
    private int skipped;
    private int excludedCount;
    private int syncId = -1;
    private int pendingCount;
    private long nextAt;
    private long deadline;
    private long quietUntil;

    public ScanSession(List<String> names, int threshold, int delayMs, int timeoutMs,
                       long now, Listener listener) {
        this(names, threshold, delayMs, timeoutMs, now, listener, name -> false);
    }

    public ScanSession(List<String> names, int threshold, int delayMs, int timeoutMs,
                       long now, Listener listener, Predicate<String> excluded) {
        this.names = List.copyOf(new LinkedHashSet<>(names));
        this.lowerNames = new LinkedHashSet<>();
        this.names.forEach(name -> lowerNames.add(name.toLowerCase(Locale.ROOT)));
        if (threshold < 1 || delayMs < 0 || timeoutMs < 500) throw new IllegalArgumentException();
        this.threshold = threshold;
        this.delayMs = delayMs;
        this.timeoutMs = timeoutMs;
        this.nextAt = now;
        this.listener = listener;
        this.excluded = excluded;
        this.timings = new ScanTimings(now);
    }

    public void tick(long now) {
        if (!active()) return;
        if (phase == Phase.SETTLING && now >= quietUntil) {
            if (pendingCount >= threshold && !excluded.test(current())) matches.add(current());
            timings.advanced(now);
            advance(now, delayMs);
        } else if (waiting() && now >= deadline) {
            stop(Failure.TIMEOUT, "Нет полного ответа /invsee для " + current() + " за " + timeoutMs
                    + " мс. Проверка остановлена; увеличьте /spidicard timeout и запустите снова.");
        }
        if (phase == Phase.READY && now >= nextAt) {
            while (index < names.size() && excluded.test(current())) {
                excludedCount++;
                index++;
            }
            if (index == names.size()) {
                phase = Phase.FINISHED;
                timings.finish(now);
                listener.finish(true, "Проверка завершена.");
            } else {
                phase = Phase.WAIT_OPEN;
                deadline = now + timeoutMs;
                timings.request(now);
                listener.request(current());
            }
        }
    }

    public void opened(int id, String title, long now) {
        if (!active()) return;
        if (phase != Phase.WAIT_OPEN) {
            stop("Открыто постороннее или повторное окно. Проверка остановлена.");
            return;
        }
        if (now >= deadline) {
            // A packet callback can run before END_CLIENT_TICK after a long frame.
            // Check the actual deadline here, not only in tick(), and close this late window.
            syncId = id;
            stop(Failure.TIMEOUT, "Позднее окно /invsee для " + current() + ": время ожидания истекло.");
            return;
        }
        // Some /invsee plugins use a generic title; those have no target identifier.
        // For titles containing a tab-list name, reject a response for another player.
        var tokens = NAME_TOKEN.matcher(CardNames.normalize(title));
        boolean targetInTitle = false;
        boolean otherPlayerInTitle = false;
        while (tokens.find()) {
            String token = tokens.group().toLowerCase(Locale.ROOT);
            if (token.equals(current().toLowerCase(Locale.ROOT))) targetInTitle = true;
            else if (lowerNames.contains(token)) otherPlayerInTitle = true;
        }
        if (otherPlayerInTitle && !targetInTitle) {
            stop("Заголовок /invsee относится к другому игроку: " + title);
            return;
        }
        syncId = id;
        timings.opened(now);
        phase = Phase.WAIT_CONTENTS;
        deadline = now + timeoutMs;
    }

    public void contents(int id, int count, long now) {
        if (id != syncId || (phase != Phase.WAIT_CONTENTS && phase != Phase.SETTLING)) return;
        if (phase == Phase.WAIT_CONTENTS && now >= deadline) {
            stop(Failure.TIMEOUT, "Позднее содержимое /invsee для " + current() + ": время ожидания истекло.");
            return;
        }
        pendingCount = count;
        timings.contents(now);
        phase = Phase.SETTLING;
        quietUntil = now + 50;
    }

    public void slotUpdated(int id, int count, long now) {
        if (id != syncId || phase != Phase.SETTLING) return;
        pendingCount = count;
        timings.slotUpdated(now);
        quietUntil = now + 50;
    }

    /** A definite "player offline/not found" command reply is safe to skip. */
    public void unavailable(long now) {
        if (phase != Phase.WAIT_OPEN) return;
        skipped++;
        timings.unavailable(now);
        advance(now, Math.max(delayMs, 750));
    }

    private void advance(long now, int delay) {
        int closedId = syncId;
        syncId = -1;
        index++;
        phase = Phase.READY;
        nextAt = now + delay;
        if (closedId >= 0) listener.close(closedId);
        if (index % 25 == 0 || index == names.size()) listener.progress();
    }

    public void stop(String reason) {
        stop(Failure.OTHER, reason);
    }

    public void stop(Failure failure, String reason) {
        if (!active()) return;
        this.failure = failure;
        int closedId = syncId;
        syncId = -1;
        phase = Phase.FINISHED;
        // Cancellation has no supplied clock; the integration records its final snapshot.
        if (closedId >= 0) listener.close(closedId);
        listener.finish(false, reason);
    }

    public boolean active() { return phase != Phase.FINISHED; }
    public boolean waiting() { return phase == Phase.WAIT_OPEN || phase == Phase.WAIT_CONTENTS || phase == Phase.SETTLING; }
    public boolean owns(int id) { return active() && id >= 0 && id == syncId; }
    public Phase phase() { return phase; }
    public String current() { return index < names.size() ? names.get(index) : ""; }
    public int checked() { return index - skipped - excludedCount; }
    public int processed() { return index; }
    public int skipped() { return skipped; }
    public int total() { return names.size(); }
    public int threshold() { return threshold; }
    public int excludedCount() { return excludedCount; }
    public Failure failure() { return failure; }
    public List<String> matches() { return matches.stream().filter(name -> !excluded.test(name)).toList(); }
    public ScanTimings.Snapshot timings(long now) { return timings.snapshot(now); }
    public void finishTimings(long now) { timings.finish(now); }
}
