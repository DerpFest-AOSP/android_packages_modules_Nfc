/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.nfc;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Write-ahead baseline, never an active card. Storage must replace the file atomically. */
final class NfcATagRecoveryJournal {
    interface Storage {
        byte[] read() throws IOException;

        void write(byte[] bytes) throws IOException;

        void clear() throws IOException;
    }

    private static final int MAGIC = 0x4e464341;
    private final Storage mStorage;

    NfcATagRecoveryJournal(Storage storage) {
        mStorage = storage;
    }

    LinkedHashMap<Integer, byte[]> load() throws IOException {
        byte[] bytes = mStorage.read();
        if (bytes == null) return new LinkedHashMap<>();
        if (bytes.length < 17 || bytes.length > 512) throw new IOException("Invalid journal size");
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != MAGIC || input.readInt() != 1) {
            throw new IOException("Unknown recovery journal version");
        }
        int count = input.readUnsignedByte();
        if (count < 5 || count > 7) throw new IOException("Invalid parameter count");
        LinkedHashMap<Integer, byte[]> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int id = input.readUnsignedByte();
            int length = input.readUnsignedByte();
            if (id != 0x85 && (id < 0x30 || id > 0x33) && id != 0x58 && id != 0x59) {
                throw new IOException("Unknown recovery parameter");
            }
            byte[] value = new byte[length];
            input.readFully(value);
            if (!NfcATagEmulationController.validValue(id, value) || values.put(id, value) != null)
                throw new IOException("Invalid parameter");
        }
        for (int id : new int[] {0x85, 0x30, 0x31, 0x32, 0x33}) {
            if (!values.containsKey(id)) throw new IOException("Incomplete baseline");
        }
        if (input.available() != 8 || input.readLong() != crc.getValue()) {
            throw new IOException("Recovery journal checksum mismatch");
        }
        return values;
    }

    void save(Map<Integer, byte[]> values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeInt(1);
        output.writeByte(values.size());
        for (Map.Entry<Integer, byte[]> entry : values.entrySet()) {
            output.writeByte(entry.getKey());
            output.writeByte(entry.getValue().length);
            output.write(entry.getValue());
        }
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        output.writeLong(crc.getValue());
        mStorage.write(bytes.toByteArray());
    }

    void clear() throws IOException {
        mStorage.clear();
    }
}
