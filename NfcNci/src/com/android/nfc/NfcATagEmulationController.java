/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.nfc;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** A reversible Type-A identity transaction. Callers serialize access with the NFC service lock. */
final class NfcATagEmulationController {
    static final int OK = 0;
    static final int FAILED = 2;
    private static final int CONFIG_CONTROL = 0x85;

    interface Configuration {
        byte[] get(int id);

        boolean set(int id, byte[] value, boolean custom);
    }

    private final Configuration mConfiguration;
    private final NfcATagRecoveryJournal mJournal;
    private boolean mJournalLoaded;
    private String mFailure;
    private int mFailedParameter = -1;
    private final LinkedHashMap<Integer, byte[]> mBaseline = new LinkedHashMap<>();
    // Error callbacks must inspect these without waiting on an in-flight NFC transaction.
    private volatile LinkedHashMap<Integer, byte[]> mActive;
    private volatile boolean mRecoveryRequired;
    private volatile long mGeneration;
    private volatile int mOwnerUid = -1;

    NfcATagEmulationController(Configuration configuration, NfcATagRecoveryJournal journal) {
        mConfiguration = configuration;
        mJournal = journal;
    }

    private boolean loadJournal() {
        if (mJournalLoaded) return true;
        try {
            mBaseline.putAll(mJournal.load());
            mRecoveryRequired = !mBaseline.isEmpty();
            mJournalLoaded = true;
            return true;
        } catch (IOException e) {
            mRecoveryRequired = true;
            mFailure = "journal_unavailable";
            return false;
        }
    }

    String failureReason() {
        return mFailure;
    }

    int failedParameter() {
        return mFailedParameter;
    }

    int apply(
            int ownerUid,
            byte bitFrameSdd,
            byte platformConfig,
            byte selInfo,
            byte[] uid,
            byte rats,
            byte[] historicalBytes) {
        validate(uid, historicalBytes);
        mFailure = null;
        mFailedParameter = -1;
        if (!loadJournal()) return FAILED;
        if (mRecoveryRequired) {
            mFailure = "recovery_required";
            return FAILED;
        }

        // Restore parameters no longer used by the next card, including Type-4-only parameters.
        LinkedHashMap<Integer, byte[]> target = copy(mBaseline);
        target.put(CONFIG_CONTROL, new byte[] {0});
        target.put(0x30, new byte[] {bitFrameSdd});
        target.put(0x31, new byte[] {platformConfig});
        target.put(0x32, new byte[] {selInfo});
        target.put(0x33, uid.clone());
        if ((selInfo & 0x20) != 0) {
            target.put(0x58, new byte[] {rats});
            if (historicalBytes != null) target.put(0x59, historicalBytes.clone());
        }

        LinkedHashMap<Integer, byte[]> previous = read(target);
        if (previous == null) return FAILED; // Never write without a complete rollback snapshot.
        LinkedHashMap<Integer, byte[]> baseline = copy(mBaseline);
        for (Map.Entry<Integer, byte[]> entry : previous.entrySet()) {
            baseline.putIfAbsent(entry.getKey(), entry.getValue().clone());
        }
        // The complete original baseline must survive process death before the first write.
        try {
            mJournal.save(baseline);
        } catch (IOException e) {
            mFailure = "journal_unavailable";
            return FAILED;
        }
        mBaseline.clear();
        mBaseline.putAll(baseline);
        // Also fail closed if the driver throws instead of returning a status.
        mRecoveryRequired = true;
        if (!write(target, true, true)) {
            String failure = mFailure;
            int parameter = mFailedParameter;
            if (!write(previous, mActive != null, false)) {
                failure = "rollback_failed";
                mRecoveryRequired = true;
                mActive = null;
                mOwnerUid = -1;
            } else {
                mRecoveryRequired = false;
                if (mActive == null) {
                    try {
                        mJournal.clear();
                        mBaseline.clear();
                    } catch (IOException e) {
                        mRecoveryRequired = true;
                        failure = "journal_unavailable";
                    }
                }
            }
            mFailure = failure;
            mFailedParameter = parameter;
            mGeneration++;
            return FAILED;
        }
        mActive = copy(target);
        mRecoveryRequired = false;
        mFailure = null;
        mFailedParameter = -1;
        mOwnerUid = ownerUid;
        mGeneration++;
        return OK;
    }

    int restore() {
        mFailure = null;
        mFailedParameter = -1;
        if (!loadJournal()) return FAILED;
        if (mBaseline.isEmpty()) return mRecoveryRequired ? FAILED : OK;
        mRecoveryRequired = true;
        if (!write(mBaseline, false, false)) {
            mRecoveryRequired = true;
            mActive = null;
            mOwnerUid = -1;
            mGeneration++;
            return FAILED;
        }
        try {
            mJournal.clear();
        } catch (IOException e) {
            mFailure = "journal_unavailable";
            mRecoveryRequired = true;
            mActive = null;
            mOwnerUid = -1;
            mGeneration++;
            return FAILED;
        }
        clearRestoredSession();
        return OK;
    }

    /**
     * Replaces an unreadable journal after the controller has been reset.
     * The values read here are the reset baseline. Call this only after that reset;
     * otherwise a custom identity still on the controller would be treated as original.
     *
     * @return true when the journal was removed and discovery may resume
     */
    boolean recaptureBaselineAfterReset() {
        LinkedHashMap<Integer, byte[]> baseline = new LinkedHashMap<>();
        for (int id : new int[] {CONFIG_CONTROL, 0x30, 0x31, 0x32, 0x33}) {
            byte[] value = mConfiguration.get(id);
            if (!validValue(id, value)) {
                mFailure = "snapshot_unavailable";
                mFailedParameter = id;
                mRecoveryRequired = true;
                return false;
            }
            baseline.put(id, value.clone());
        }
        for (int id : new int[] {0x58, 0x59}) {
            byte[] value = mConfiguration.get(id);
            if (value == null) continue;
            if (!validValue(id, value)) {
                mFailure = "snapshot_unavailable";
                mFailedParameter = id;
                mRecoveryRequired = true;
                return false;
            }
            baseline.put(id, value.clone());
        }
        try {
            // Persist and reload before deleting, so a failed commit cannot drop recovery.
            mJournal.save(baseline);
            mBaseline.clear();
            mJournalLoaded = false;
            if (!loadJournal()) return false;
            mJournal.clear();
        } catch (IOException e) {
            mFailure = "journal_unavailable";
            mRecoveryRequired = true;
            return false;
        }
        clearRestoredSession();
        return true;
    }

    private void clearRestoredSession() {
        mFailure = null;
        mFailedParameter = -1;
        mBaseline.clear();
        mActive = null;
        mOwnerUid = -1;
        mRecoveryRequired = false;
        mGeneration++;
    }

    boolean isActive() {
        return mActive != null && !mRecoveryRequired;
    }

    boolean needsRecovery() {
        return !mJournalLoaded || mRecoveryRequired;
    }

    long generation() {
        return mGeneration;
    }

    int ownerUid() {
        return mOwnerUid;
    }

    byte[] activeParameter(int id) {
        byte[] value = mActive == null ? null : mActive.get(id);
        return value == null ? null : value.clone();
    }

    static void validate(byte[] uid, byte[] historicalBytes) {
        if (uid == null || (uid.length != 4 && uid.length != 7 && uid.length != 10)) {
            throw new IllegalArgumentException("NFCID1 must contain 4, 7 or 10 bytes");
        }
        if (historicalBytes != null && historicalBytes.length > 15) {
            throw new IllegalArgumentException("Historical bytes must not exceed 15 bytes");
        }
    }

    private LinkedHashMap<Integer, byte[]> read(Map<Integer, byte[]> target) {
        LinkedHashMap<Integer, byte[]> result = new LinkedHashMap<>();
        for (int id : target.keySet()) {
            byte[] value = mConfiguration.get(id);
            if (!validValue(id, value)) {
                mFailure = "snapshot_unavailable";
                mFailedParameter = id;
                return null;
            }
            result.put(id, value.clone());
        }
        return result;
    }

    static boolean validValue(int id, byte[] value) {
        if (value == null) return false;
        if (id == 0x33) {
            return value.length == 0
                    || value.length == 4
                    || value.length == 7
                    || value.length == 10;
        }
        return id == 0x59 ? value.length <= 15 : value.length == 1;
    }

    private boolean write(Map<Integer, byte[]> values, boolean custom, boolean failFast) {
        boolean success = true;
        // Restore every saved parameter even when an earlier parameter is rejected.
        for (Map.Entry<Integer, byte[]> entry : values.entrySet()) {
            int id = entry.getKey();
            byte[] value = entry.getValue();
            boolean accepted;
            boolean matches;
            try {
                accepted = mConfiguration.set(id, value.clone(), custom);
                matches = accepted && Arrays.equals(value, mConfiguration.get(id));
            } catch (RuntimeException e) {
                mActive = null;
                mOwnerUid = -1;
                mGeneration++;
                mFailure = "recovery_required";
                mFailedParameter = id;
                throw e;
            }
            if (!matches) {
                if (success) {
                    mFailure = accepted ? "readback_mismatch" : "config_rejected";
                    mFailedParameter = id;
                }
                success = false;
                if (failFast) break;
            }
        }
        return success;
    }

    private static LinkedHashMap<Integer, byte[]> copy(Map<Integer, byte[]> source) {
        LinkedHashMap<Integer, byte[]> result = new LinkedHashMap<>();
        source.forEach((id, value) -> result.put(id, value.clone()));
        return result;
    }
}
