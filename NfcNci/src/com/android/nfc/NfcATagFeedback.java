/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.nfc;

/** Haptics for a custom identity. Field detection never means access was granted. */
final class NfcATagFeedback {
    static final int FIELD_DURATION_MS = 35;
    static final int ERROR_DURATION_MS = 250;
    private static final long FIELD_GAP_MS = 750;
    private static final long ERROR_GAP_MS = 1000;

    private long mGeneration = -1;
    private long mLastFieldOff = -1;
    private long mLastError = -1;
    private boolean mInField;

    private boolean prepare(boolean active, long generation) {
        if (!active || generation != mGeneration) {
            mInField = false;
            mLastFieldOff = -1;
            mLastError = -1;
            mGeneration = active ? generation : -1;
        }
        return active;
    }

    synchronized int fieldChanged(boolean active, long generation, boolean on, long now) {
        if (!prepare(active, generation)) return 0;
        if (!on) {
            if (mInField) mLastFieldOff = now;
            mInField = false;
            return 0;
        }
        if (mInField) return 0;
        mInField = true;
        return mLastFieldOff >= 0 && now - mLastFieldOff < FIELD_GAP_MS ? 0 : FIELD_DURATION_MS;
    }

    synchronized int error(boolean active, long generation, long now) {
        if (!prepare(active, generation)) return 0;
        if (mLastError >= 0 && now - mLastError < ERROR_GAP_MS) return 0;
        mLastError = now;
        return ERROR_DURATION_MS;
    }
}
