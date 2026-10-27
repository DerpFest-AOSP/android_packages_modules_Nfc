/* SPDX-License-Identifier: Apache-2.0 */
package com.android.nfc;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class NfcATagEmulationControllerTest {
    private FakeConfiguration hardware;
    private NfcATagEmulationController controller;
    private MemoryJournal journal;
    private final byte[] uid = {1, 2, 3, 4};

    @Before
    public void setUp() {
        hardware = new FakeConfiguration();
        journal = new MemoryJournal();
        controller = recreate();
    }

    private int apply(byte[] id) {
        return controller.apply(10042, (byte) 4, (byte) 0, (byte) 8, id, (byte) 0, null);
    }

    @Test
    public void restoresOriginalIdentityAfterSeveralCardSwitches() {
        Map<Integer, byte[]> original = hardware.snapshot();
        assertEquals(0, apply(uid));
        assertEquals(0, apply(new byte[] {4, 3, 2, 1, 5, 6, 7}));
        assertEquals(0, controller.restore());
        original.forEach((id, bytes) -> assertArrayEquals(bytes, hardware.values.get(id)));
        assertFalse(controller.isActive());
    }

    @Test
    public void missingSnapshotNeverWrites() {
        hardware.values.remove(0x31);
        assertEquals(2, apply(uid));
        assertEquals(0, hardware.writes);
    }

    @Test
    public void allValidUidBoundariesRoundTripAndKeepOwner() {
        for (int length : new int[] {4, 7, 10}) {
            byte[] identity = new byte[length];
            identity[0] = (byte) length;
            assertEquals(0, apply(identity));
            assertEquals(10042, controller.ownerUid());
            assertArrayEquals(identity, controller.activeParameter(0x33));
            assertEquals(0, controller.restore());
            assertEquals(-1, controller.ownerUid());
        }
    }

    @Test
    public void interruptedReadBeforeSwitchKeepsPreviousCard() {
        assertEquals(0, apply(uid));
        hardware.values.remove(0x31);
        int writes = hardware.writes;
        assertEquals(2, apply(new byte[] {9, 8, 7, 6}));
        assertEquals(writes, hardware.writes);
        assertTrue(controller.isActive());
        assertArrayEquals(uid, controller.activeParameter(0x33));
        assertEquals(10042, controller.ownerUid());
    }

    @Test
    public void failedRestoreStillAttemptsEverySavedParameterAndCanRetry() {
        assertEquals(0, apply(uid));
        hardware.rejectNextId = 0x85;
        int writes = hardware.writes;
        assertEquals(2, controller.restore());
        assertEquals(5, hardware.writes - writes);
        assertTrue(controller.needsRecovery());
        assertFalse(controller.isActive());
        assertEquals(0, controller.restore());
        assertFalse(controller.needsRecovery());
        assertArrayEquals(new byte[0], hardware.values.get(0x33));
    }

    @Test
    public void rejectedSettingRollsBackAndPreservesPreviousCard() {
        assertEquals(0, apply(uid));
        hardware.rejectNextId = 0x33;
        assertEquals(2, apply(new byte[] {9, 8, 7, 6}));
        assertTrue(controller.isActive());
        assertArrayEquals(uid, controller.activeParameter(0x33));
        assertArrayEquals(uid, hardware.values.get(0x33));
    }

    @Test
    public void acknowledgedButIgnoredWriteIsNotSuccess() {
        hardware.ignoreNextId = 0x33;
        assertEquals(2, apply(uid));
        assertFalse(controller.isActive());
        assertFalse(controller.needsRecovery());
        assertArrayEquals(new byte[0], hardware.values.get(0x33));
    }

    @Test
    public void lostChannelRequiresRecoveryAndRefusesNewApply() {
        hardware.rejectPermanently = true;
        assertEquals(2, apply(uid));
        assertTrue(controller.needsRecovery());
        int writes = hardware.writes;
        assertEquals(2, apply(uid));
        assertEquals(writes, hardware.writes);
    }

    @Test
    public void activeArraysAreDefensivelyCopied() {
        assertEquals(0, apply(uid));
        uid[0] = 99;
        byte[] active = controller.activeParameter(0x33);
        assertEquals(1, active[0]);
        active[0] = 55;
        assertEquals(1, controller.activeParameter(0x33)[0]);
    }

    @Test
    public void invalidUidAndHistoricalBytesNeverWrite() {
        for (int length : new int[] {0, 1, 3, 5, 8, 11, 255}) {
            assertThrows(IllegalArgumentException.class, () -> apply(new byte[length]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.apply(
                                42, (byte) 4, (byte) 0, (byte) 0x20, uid, (byte) 0, new byte[16]));
        assertEquals(0, hardware.writes);
    }

    @Test
    public void typeFourParametersAreRestoredWhenChangingToLegacyCard() {
        assertEquals(
                0,
                controller.apply(
                        42, (byte) 4, (byte) 0, (byte) 0x20, uid, (byte) 0x40, new byte[] {1, 2}));
        assertEquals(0, apply(uid));
        assertArrayEquals(new byte[] {0x11}, hardware.values.get(0x58));
        assertArrayEquals(new byte[] {3}, hardware.values.get(0x59));
    }

    private NfcATagEmulationController recreate() {
        return new NfcATagEmulationController(hardware, new NfcATagRecoveryJournal(journal));
    }

    @Test
    public void processRestartRestoresOriginalBaselineBeforeNewApply() {
        Map<Integer, byte[]> original = hardware.snapshot();
        assertEquals(0, apply(uid));
        assertNotNull(journal.bytes);
        controller = recreate();
        assertTrue(controller.needsRecovery());
        int writes = hardware.writes;
        assertEquals(2, apply(uid));
        assertEquals(writes, hardware.writes);
        assertEquals(0, controller.restore());
        assertNull(journal.bytes);
        original.forEach((id, bytes) -> assertArrayEquals(bytes, hardware.values.get(id)));
        assertFalse(controller.isActive());
        assertFalse(controller.needsRecovery());
        assertEquals(-1, controller.ownerUid());
    }

    @Test
    public void journalIsDurableBeforeAnyControllerWrite() {
        hardware.beforeWrite = () -> assertNotNull(journal.bytes);
        assertEquals(0, apply(uid));
    }

    @Test
    public void failedJournalWriteNeverChangesHardwareOrPreviousSession() {
        journal.failWrite = true;
        assertEquals(2, apply(uid));
        assertEquals(0, hardware.writes);
        assertEquals("journal_unavailable", controller.failureReason());
        journal.failWrite = false;
        assertEquals(0, apply(uid));
        journal.failWrite = true;
        int writes = hardware.writes;
        assertEquals(2, apply(new byte[] {4, 3, 2, 1}));
        assertEquals(writes, hardware.writes);
        assertTrue(controller.isActive());
        assertArrayEquals(uid, controller.activeParameter(0x33));
    }

    @Test
    public void failedJournalDeleteBlocksDiscoveryUntilRetried() {
        assertEquals(0, apply(uid));
        journal.failClear = true;
        assertEquals(2, controller.restore());
        assertTrue(controller.needsRecovery());
        assertFalse(controller.isActive());
        assertNotNull(journal.bytes);
        journal.failClear = false;
        assertEquals(0, controller.restore());
        assertFalse(controller.needsRecovery());
        assertNull(journal.bytes);
    }

    @Test
    public void unreadableJournalIsClearedAfterVerifiedResetBaseline() {
        Map<Integer, byte[]> original = hardware.snapshot();
        assertEquals(0, apply(uid));
        journal.bytes[0] ^= 99;
        controller = recreate();
        hardware.values.clear();
        hardware.values.putAll(original);
        int writes = hardware.writes;
        assertTrue(controller.recaptureBaselineAfterReset());
        assertEquals(writes, hardware.writes);
        assertFalse(controller.needsRecovery());
        assertFalse(controller.isActive());
        assertNull(journal.bytes);
        assertEquals(-1, controller.ownerUid());
        assertEquals(0, apply(uid));
    }

    @Test
    public void unreadableJournalStaysBlockedWhenResetBaselineCannotBeRead() {
        assertEquals(0, apply(uid));
        byte[] corrupt = journal.bytes.clone();
        corrupt[0] ^= 99;
        journal.bytes = corrupt;
        controller = recreate();
        hardware.values.remove(0x31);
        int writes = hardware.writes;
        assertFalse(controller.recaptureBaselineAfterReset());
        assertEquals(writes, hardware.writes);
        assertTrue(controller.needsRecovery());
        assertEquals("snapshot_unavailable", controller.failureReason());
        assertArrayEquals(corrupt, journal.bytes);
        assertEquals(2, controller.restore());
        assertEquals(writes, hardware.writes);
    }

    @Test
    public void corruptAndUnknownJournalsNeverWriteController() {
        assertEquals(0, apply(uid));
        byte[] valid = journal.bytes.clone();
        for (int index : new int[] {0, 7, valid.length - 1}) {
            journal.bytes = valid.clone();
            journal.bytes[index] ^= 99;
            controller = recreate();
            int writes = hardware.writes;
            assertEquals(2, controller.restore());
            assertEquals(2, apply(uid));
            assertEquals(writes, hardware.writes);
            assertTrue(controller.needsRecovery());
            assertEquals("journal_unavailable", controller.failureReason());
            assertNotNull(journal.bytes);
        }
    }

    @Test
    public void restoreFailureRetainsJournalAcrossAnotherProcessRestart() {
        assertEquals(0, apply(uid));
        hardware.rejectPermanently = true;
        assertEquals(2, controller.restore());
        assertNotNull(journal.bytes);
        controller = recreate();
        hardware.rejectPermanently = false;
        assertEquals(0, controller.restore());
        assertNull(journal.bytes);
        assertArrayEquals(new byte[0], hardware.values.get(0x33));
    }

    @Test
    public void diagnosticsKeepRejectedParameterAndReadbackFailure() {
        hardware.ignoreNextId = 0x33;
        assertEquals(2, apply(uid));
        assertEquals("readback_mismatch", controller.failureReason());
        assertEquals(0x33, controller.failedParameter());
        assertEquals(0, apply(uid));
        assertNull(controller.failureReason());
    }

    @Test
    public void driverExceptionDuringSwitchNeverKeepsPreviousCardActive() {
        assertEquals(0, apply(uid));
        hardware.throwNextId = 0x33;
        assertThrows(IllegalStateException.class, () -> apply(new byte[] {9, 8, 7, 6}));
        assertFalse(controller.isActive());
        assertTrue(controller.needsRecovery());
        assertNotNull(journal.bytes);
        assertEquals("recovery_required", controller.failureReason());
        int writes = hardware.writes;
        assertEquals(2, apply(uid));
        assertEquals(writes, hardware.writes);
        assertEquals(0, controller.restore());
        assertNull(journal.bytes);
        assertArrayEquals(new byte[0], hardware.values.get(0x33));
    }

    @Test
    public void driverExceptionDuringRestoreRetainsJournalAndCanRetry() {
        assertEquals(0, apply(uid));
        hardware.throwNextId = 0x33;
        assertThrows(IllegalStateException.class, () -> controller.restore());
        assertTrue(controller.needsRecovery());
        assertFalse(controller.isActive());
        assertNotNull(journal.bytes);
        assertEquals(0x33, controller.failedParameter());
        assertEquals(0, controller.restore());
        assertFalse(controller.needsRecovery());
        assertNull(controller.failureReason());
        assertNull(journal.bytes);
    }

    private static class MemoryJournal implements NfcATagRecoveryJournal.Storage {
        byte[] bytes;
        boolean failWrite;
        boolean failClear;

        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        public void write(byte[] value) throws IOException {
            if (failWrite) throw new IOException("Test storage unavailable");
            bytes = value.clone();
        }

        public void clear() throws IOException {
            if (failClear) throw new IOException("Test deletion unavailable");
            bytes = null;
        }
    }

    private static class FakeConfiguration implements NfcATagEmulationController.Configuration {
        final Map<Integer, byte[]> values = new HashMap<>();
        int rejectNextId = -1;
        int throwNextId = -1;
        int ignoreNextId = -1;
        int writes;
        Runnable beforeWrite;
        boolean rejectPermanently;

        FakeConfiguration() {
            values.put(0x85, new byte[] {1});
            values.put(0x30, new byte[] {4});
            values.put(0x31, new byte[] {0});
            values.put(0x32, new byte[] {0x20});
            values.put(0x33, new byte[0]);
            values.put(0x58, new byte[] {0x11});
            values.put(0x59, new byte[] {3});
        }

        public byte[] get(int id) {
            byte[] value = values.get(id);
            return value == null ? null : value.clone();
        }

        public boolean set(int id, byte[] value, boolean custom) {
            if (beforeWrite != null) beforeWrite.run();
            writes++;
            if (rejectPermanently) return false;
            if (rejectNextId == id) {
                rejectNextId = -1;
                return false;
            }
            if (ignoreNextId == id) {
                ignoreNextId = -1;
                return true;
            }
            values.put(id, value.clone());
            if (throwNextId == id) {
                throwNextId = -1;
                throw new IllegalStateException("Test driver exception after write");
            }
            return true;
        }

        Map<Integer, byte[]> snapshot() {
            Map<Integer, byte[]> result = new HashMap<>();
            values.forEach((id, bytes) -> result.put(id, bytes.clone()));
            return result;
        }
    }
}
