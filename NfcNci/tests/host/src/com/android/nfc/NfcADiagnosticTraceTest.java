package com.android.nfc;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class NfcADiagnosticTraceTest {
    private final AtomicLong wall = new AtomicLong(1_000_000);
    private final AtomicLong elapsed = new AtomicLong(1);
    private final NfcADiagnosticTrace trace = new NfcADiagnosticTrace(wall::get, elapsed::get);

    @Test
    public void disabledDoesNotRecord() {
        trace.record(NfcADiagnosticTrace.Event.FIELD_ON, 1, 0);
        assertEquals("recording=false\n", trace.export(10000));
    }

    @Test
    public void ownerIsolationAndExplicitStop() {
        assertTrue(trace.enable(10000, true));
        trace.record(NfcADiagnosticTrace.Event.HOST_DATA, 12, 1);
        assertTrue(trace.export(10000).contains("HOST_DATA value=12 extra=1"));
        assertEquals("recording=false\n", trace.export(11000));
        assertFalse(trace.enable(11000, false));
        assertFalse(trace.enable(11000, true));
        assertTrue(trace.enable(10000, false));
        assertEquals("recording=false\n", trace.export(10000));
    }

    @Test
    public void leaseUsesElapsedTimeAndRenewalKeepsHistory() {
        trace.enable(10000, true);
        elapsed.addAndGet(60_000);
        wall.set(0);
        assertTrue(trace.enable(10000, true));
        elapsed.addAndGet(60_000);
        assertTrue(trace.export(10000).contains("event=START"));
        elapsed.addAndGet(60_000);
        assertEquals("recording=false\n", trace.export(10000));
        assertTrue(trace.enable(11000, true));
    }

    @Test
    public void boundedRingAndClearCannotExposeOldEvents() {
        trace.enable(10000, true);
        for (int i = 0; i < 700; i++) trace.record(NfcADiagnosticTrace.Event.FIELD_ON, i, 0);
        String result = trace.export(10000);
        assertEquals(513, result.lines().count());
        assertFalse(result.contains("event=START"));
        assertTrue(result.contains("dropped=189"));
        assertTrue(result.contains("value=699 "));
        trace.clear();
        trace.enable(10000, true);
        assertFalse(trace.export(10000).contains("value=699 "));
    }
}
