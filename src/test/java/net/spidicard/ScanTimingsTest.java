package net.spidicard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanTimingsTest {
    @Test void measuresAllStagesAndRequestGapsWithoutViewerTime() {
        var timings = new ScanTimings(0);
        timings.request(0);
        timings.opened(70);
        timings.contents(80);
        timings.advanced(140);
        timings.request(150);
        timings.opened(220);
        timings.contents(230);
        timings.advanced(290);
        assertEquals(295, timings.snapshot(295).elapsedMs());
        timings.finish(300);
        var result = timings.snapshot(1000);
        assertEquals(2, result.requested());
        assertEquals(2, result.opened());
        assertEquals(2, result.receivedFull());
        assertEquals(2, result.completed());
        assertEquals(0, result.unavailable());
        assertEquals(0, result.unfinished());
        assertEquals(0, result.inFlightMs());
        assertEquals(300, result.elapsedMs());
        assertEquals(140, result.requestToOpenMs());
        assertEquals(20, result.openToFullMs());
        assertEquals(120, result.fullToAdvanceMs());
        assertEquals(120, result.lastUpdateToAdvanceMs());
        assertEquals(280, result.requestToAdvanceMs());
        assertEquals(10, result.betweenRequestsMs());
        assertEquals(70, result.averageRequestToOpenMs());
        assertEquals(10, result.averageOpenToFullMs());
        assertEquals(60, result.averageFullToAdvanceMs());
        assertEquals(140, result.averageRequestToAdvanceMs());
        assertEquals(60, result.averageLastUpdateToAdvanceMs());
        assertEquals(2 * 1000.0 / 300, result.playersPerSecond(), 0.000001);
        assertTrue(result.summary().contains("request->open=70.00 ms"));
    }

    @Test void repeatedFullAndSlotUpdatesRemainPartOfFirstFullToAdvanceWait() {
        var timings = new ScanTimings(100);
        timings.request(100);
        timings.opened(120);
        timings.contents(130);
        timings.slotUpdated(150);
        timings.contents(160);
        timings.slotUpdated(180);
        timings.advanced(230);
        timings.finish(240);
        var result = timings.snapshot(240);
        assertEquals(1, result.receivedFull());
        assertEquals(2, result.fullPackets());
        assertEquals(2, result.slotUpdates());
        assertEquals(20, result.requestToOpenMs());
        assertEquals(10, result.openToFullMs());
        assertEquals(100, result.fullToAdvanceMs());
        assertEquals(50, result.lastUpdateToAdvanceMs());
        assertEquals(130, result.requestToAdvanceMs());
        assertEquals(140, result.elapsedMs());
    }

    @Test void unavailableRequestsMeasureResponseAndGuardWithoutInventingSuccessfulPlayers() {
        var timings = new ScanTimings(0);
        timings.request(10);
        timings.unavailable(50);
        timings.request(800);
        timings.opened(820);
        timings.contents(830);
        timings.advanced(880);
        timings.finish(890);
        var result = timings.snapshot(890);
        assertEquals(2, result.requested());
        assertEquals(1, result.completed());
        assertEquals(1, result.unavailable());
        assertEquals(1, result.opened());
        assertEquals(1, result.receivedFull());
        assertEquals(0, result.unfinished());
        assertEquals(40, result.requestToUnavailableMs());
        assertEquals(750, result.betweenRequestsMs());
        assertEquals(20, result.averageRequestToOpenMs());
        assertEquals(10, result.averageOpenToFullMs());
        assertEquals(50, result.averageFullToAdvanceMs());
        assertEquals(80, result.averageRequestToAdvanceMs());
        assertEquals(1000.0 / 890, result.playersPerSecond(), 0.000001);
    }

    @Test void interruptionKeepsIncompleteRequestVisibleAndFreezesEveryMetric() {
        var timings = new ScanTimings(100);
        timings.request(110);
        timings.opened(130);
        timings.contents(140);
        timings.slotUpdated(160);
        timings.finish(180);
        var stopped = timings.snapshot(1000);
        assertEquals(80, stopped.elapsedMs());
        assertEquals(70, stopped.inFlightMs());
        assertEquals(1, stopped.unfinished());
        assertEquals(0, stopped.completed());
        assertEquals(20, stopped.requestToOpenMs());
        assertEquals(10, stopped.openToFullMs());
        assertEquals(0, stopped.fullToAdvanceMs());
        assertEquals(0, stopped.playersPerSecond());
        timings.advanced(200);
        timings.contents(210);
        timings.slotUpdated(220);
        timings.unavailable(230);
        timings.request(240);
        timings.opened(250);
        timings.finish(260);
        assertEquals(stopped, timings.snapshot(2000));
    }

    @Test void duplicateAndOutOfOrderDiagnosticEventsAreHarmless() {
        var timings = new ScanTimings(0);
        timings.opened(10);
        timings.contents(20);
        timings.advanced(30);
        timings.unavailable(40);
        assertEquals(0, timings.snapshot(40).requested());
        timings.request(50);
        timings.request(60);
        timings.slotUpdated(70);
        timings.advanced(80);
        timings.opened(90);
        timings.opened(100);
        timings.contents(110);
        timings.advanced(160);
        timings.advanced(170);
        timings.contents(180);
        timings.slotUpdated(190);
        timings.unavailable(200);
        var result = timings.snapshot(200);
        assertEquals(1, result.requested());
        assertEquals(1, result.opened());
        assertEquals(1, result.receivedFull());
        assertEquals(1, result.fullPackets());
        assertEquals(0, result.slotUpdates());
        assertEquals(1, result.completed());
        assertEquals(0, result.unavailable());
        assertEquals(40, result.requestToOpenMs());
        assertEquals(20, result.openToFullMs());
        assertEquals(50, result.fullToAdvanceMs());
        assertEquals(110, result.requestToAdvanceMs());
    }

    @Test void snapshotsDoNotMutateAnActiveRequestAndZeroDurationHasFiniteAverages() {
        var timings = new ScanTimings(0);
        assertEquals(0, timings.snapshot(0).playersPerSecond());
        assertEquals(0, timings.snapshot(0).averageRequestToOpenMs());
        assertFalse(timings.snapshot(0).summary().contains("NaN"));
        assertFalse(timings.snapshot(0).summary().contains("Infinity"));
        timings.request(10);
        timings.opened(20);
        timings.contents(30);
        assertEquals(30, timings.snapshot(40).inFlightMs());
        assertEquals(40, timings.snapshot(50).inFlightMs());
        timings.advanced(80);
        assertEquals(70, timings.snapshot(80).requestToAdvanceMs());
        assertEquals(0, timings.snapshot(80).inFlightMs());
        assertEquals(1, timings.snapshot(80).completed());
    }

    @Test void clockAnomaliesCannotProduceNegativeDurations() {
        var timings = new ScanTimings(100);
        timings.request(90);
        timings.opened(80);
        timings.contents(70);
        timings.advanced(60);
        timings.finish(50);
        var result = timings.snapshot(500);
        assertEquals(0, result.elapsedMs());
        assertEquals(0, result.requestToOpenMs());
        assertEquals(0, result.openToFullMs());
        assertEquals(0, result.fullToAdvanceMs());
        assertEquals(0, result.requestToAdvanceMs());
        assertEquals(0, result.lastUpdateToAdvanceMs());
        assertEquals(0, result.playersPerSecond());
    }
}
