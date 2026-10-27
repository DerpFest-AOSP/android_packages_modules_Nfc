/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.nfc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NfcATagFeedbackTest {
    @Test
    public void ordinaryNfcHasNoCustomIdentityFeedback() {
        NfcATagFeedback feedback = new NfcATagFeedback();
        assertEquals(0, feedback.fieldChanged(false, 1, true, 0));
        assertEquals(0, feedback.error(false, 1, 0));
    }

    @Test
    public void fieldPulseDoesNotRequireHostActivationOrDiagnostics() {
        NfcATagFeedback feedback = new NfcATagFeedback();
        assertEquals(35, feedback.fieldChanged(true, 1, true, 0));
        assertEquals(0, feedback.fieldChanged(true, 1, true, 100));
        assertEquals(0, feedback.fieldChanged(true, 1, false, 200));
    }

    @Test
    public void fieldBlipsAreOneEncounterAndLaterTapCanPulseAgain() {
        NfcATagFeedback feedback = new NfcATagFeedback();
        assertEquals(35, feedback.fieldChanged(true, 1, true, 0));
        feedback.fieldChanged(true, 1, false, 125);
        assertEquals(0, feedback.fieldChanged(true, 1, true, 160));
        feedback.fieldChanged(true, 1, false, 622);
        assertEquals(35, feedback.fieldChanged(true, 1, true, 2000));
    }

    @Test
    public void errorsHaveSeparateLongFeedbackWithoutInferringRejectionFromRemoval() {
        NfcATagFeedback feedback = new NfcATagFeedback();
        feedback.fieldChanged(true, 1, true, 0);
        assertEquals(250, feedback.error(true, 1, 100));
        assertEquals(0, feedback.error(true, 1, 500));
        assertEquals(250, feedback.error(true, 1, 1200));
        assertEquals(0, feedback.fieldChanged(true, 1, false, 1300));
    }

    @Test
    public void newIdentityOrStoppedSessionResetsFeedback() {
        NfcATagFeedback feedback = new NfcATagFeedback();
        assertEquals(35, feedback.fieldChanged(true, 1, true, 0));
        assertEquals(35, feedback.fieldChanged(true, 2, true, 100));
        assertEquals(0, feedback.fieldChanged(false, 2, true, 200));
        assertEquals(35, feedback.fieldChanged(true, 3, true, 300));
    }
}
