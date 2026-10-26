/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.nfc;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Bounded, opt-in RF metadata. The recording owner must renew its two-minute lease. */
final class NfcADiagnosticTrace {
    enum Event {
        START,
        FIELD_ON,
        FIELD_OFF,
        HOST_ACTIVATED,
        HOST_DEACTIVATED,
        HOST_DATA,
        EE_LISTEN_ON,
        EE_LISTEN_OFF,
        DISCOVERY,
        APPLY_START,
        APPLY_END,
        USER_SWITCH,
        RF_DISCOVERY
    }

    static final int MAX_EVENTS = 512;
    static final long LEASE_MS = 120_000;
    private final LongSupplier mWallClock;
    private final LongSupplier mElapsedClock;
    private final ArrayDeque<String> mEvents = new ArrayDeque<>();
    private final String mInstance = java.util.UUID.randomUUID().toString();
    private int mOwner = -1;
    private long mExpires;
    private long mSequence;
    private long mDropped;

    NfcADiagnosticTrace(LongSupplier wallClock, LongSupplier elapsedClock) {
        mWallClock = wallClock;
        mElapsedClock = elapsedClock;
    }

    synchronized boolean enable(int uid, boolean enabled) {
        expire();
        if (mOwner != -1 && mOwner != uid) return false;
        if (!enabled) {
            clear();
            return true;
        }
        boolean starting = mOwner == -1;
        mOwner = uid;
        mExpires = mElapsedClock.getAsLong() + LEASE_MS;
        if (starting) record(Event.START, 0, 0);
        return true;
    }

    synchronized void record(Event event, int value, int extra) {
        expire();
        if (mOwner == -1) return;
        if (mEvents.size() == MAX_EVENTS) {
            mEvents.removeFirst();
            mDropped++;
        }
        mEvents.addLast(
                ++mSequence
                        + " wall_ms="
                        + mWallClock.getAsLong()
                        + " elapsed_ms="
                        + mElapsedClock.getAsLong()
                        + " event="
                        + event
                        + " value="
                        + value
                        + " extra="
                        + extra);
    }

    String instance() {
        return mInstance;
    }

    synchronized String export(int uid) {
        expire();
        if (mOwner != uid) return "recording=false\n";
        return "recording=true last_sequence="
                + mSequence
                + " dropped="
                + mDropped
                + "\n"
                + String.join("\n", mEvents)
                + "\n";
    }

    synchronized void clear() {
        mOwner = -1;
        mExpires = 0;
        mEvents.clear();
        mDropped = 0;
        // Keep sequence numbers unique through lease expiry and recorder restarts.
    }

    private void expire() {
        if (mOwner != -1 && mElapsedClock.getAsLong() >= mExpires) clear();
    }
}
