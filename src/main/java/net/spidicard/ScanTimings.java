package net.spidicard;

import java.util.Locale;

/** Diagnostic timings for serialized requests; never decides when a scan can advance. */
public final class ScanTimings {
    private final long startedAt;
    private boolean finished;
    private long finishedAt;
    private boolean requesting;
    private boolean hasOpen;
    private boolean hasFull;
    private boolean hasPreviousEnd;
    private long requestedAt;
    private long openedAt;
    private long firstFullAt;
    private long lastUpdateAt;
    private long previousEndAt;
    private int requested;
    private int opened;
    private int receivedFull;
    private int completed;
    private int unavailable;
    private int fullPackets;
    private int slotUpdates;
    private long requestToOpenMs;
    private long openToFullMs;
    private long fullToAdvanceMs;
    private long requestToAdvanceMs;
    private long requestToUnavailableMs;
    private long betweenRequestsMs;
    private long lastUpdateToAdvanceMs;

    public ScanTimings(long startedAt) {
        this.startedAt = startedAt;
    }

    public void request(long now) {
        if (finished || requesting) return;
        if (hasPreviousEnd) betweenRequestsMs += elapsed(previousEndAt, now);
        requested++;
        requestedAt = now;
        requesting = true;
        hasOpen = false;
        hasFull = false;
    }

    public void opened(long now) {
        if (finished || !requesting || hasOpen) return;
        opened++;
        openedAt = now;
        hasOpen = true;
        requestToOpenMs += elapsed(requestedAt, now);
    }

    /** Repeated full packets extend the measured settling interval, not the initial response. */
    public void contents(long now) {
        if (finished || !requesting || !hasOpen) return;
        fullPackets++;
        if (!hasFull) {
            receivedFull++;
            firstFullAt = now;
            hasFull = true;
            openToFullMs += elapsed(openedAt, now);
        }
        lastUpdateAt = now;
    }

    public void slotUpdated(long now) {
        if (finished || !requesting || !hasFull) return;
        slotUpdates++;
        lastUpdateAt = now;
    }

    public void advanced(long now) {
        if (finished || !requesting || !hasFull) return;
        completed++;
        fullToAdvanceMs += elapsed(firstFullAt, now);
        lastUpdateToAdvanceMs += elapsed(lastUpdateAt, now);
        requestToAdvanceMs += elapsed(requestedAt, now);
        endRequest(now);
    }

    public void unavailable(long now) {
        if (finished || !requesting || hasOpen) return;
        unavailable++;
        requestToUnavailableMs += elapsed(requestedAt, now);
        endRequest(now);
    }

    /** Freeze elapsed time before saving results or waiting for the result viewer. */
    public void finish(long now) {
        if (finished) return;
        finished = true;
        finishedAt = now;
    }

    public Snapshot snapshot(long now) {
        long end = finished ? finishedAt : now;
        return new Snapshot(requested, opened, receivedFull, completed, unavailable, fullPackets, slotUpdates,
                elapsed(startedAt, end), requestToOpenMs, openToFullMs, fullToAdvanceMs,
                requestToAdvanceMs, requestToUnavailableMs, betweenRequestsMs, lastUpdateToAdvanceMs,
                requesting ? elapsed(requestedAt, end) : 0, requested - completed - unavailable);
    }

    private void endRequest(long now) {
        requesting = false;
        previousEndAt = now;
        hasPreviousEnd = true;
    }

    private static long elapsed(long from, long to) {
        return Math.max(0, to - from);
    }

    public record Snapshot(int requested, int opened, int receivedFull, int completed, int unavailable,
                           int fullPackets, int slotUpdates, long elapsedMs,
                           long requestToOpenMs, long openToFullMs, long fullToAdvanceMs,
                           long requestToAdvanceMs, long requestToUnavailableMs, long betweenRequestsMs,
                           long lastUpdateToAdvanceMs, long inFlightMs, int unfinished) {
        public double averageRequestToOpenMs() { return average(requestToOpenMs, opened); }
        public double averageOpenToFullMs() { return average(openToFullMs, receivedFull); }
        public double averageFullToAdvanceMs() { return average(fullToAdvanceMs, completed); }
        public double averageRequestToAdvanceMs() { return average(requestToAdvanceMs, completed); }
        public double averageLastUpdateToAdvanceMs() { return average(lastUpdateToAdvanceMs, completed); }
        public double playersPerSecond() { return elapsedMs == 0 ? 0 : completed * 1000.0 / elapsedMs; }

        public String summary() {
            return String.format(Locale.ROOT,
                    "completed=%d/%d, elapsed=%.3f s, rate=%.2f players/s, request->open=%.2f ms, "
                            + "open->full=%.2f ms, full->advance=%.2f ms, last-update->advance=%.2f ms, "
                            + "between-requests=%d ms, full-packets=%d, slot-updates=%d, unavailable=%d, unfinished=%d",
                    completed, requested, elapsedMs / 1000.0, playersPerSecond(),
                    averageRequestToOpenMs(), averageOpenToFullMs(), averageFullToAdvanceMs(),
                    averageLastUpdateToAdvanceMs(), betweenRequestsMs, fullPackets, slotUpdates, unavailable, unfinished);
        }

        private static double average(long sum, int count) {
            return count == 0 ? 0 : (double) sum / count;
        }
    }
}
