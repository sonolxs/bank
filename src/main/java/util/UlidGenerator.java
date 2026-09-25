package com.example.bank.util;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID mínimo: 48 bits de timestamp + 80 bits aleatorios, en Crockford Base32.
 * Ordenable por tiempo. Suficiente para IDs opacos con prefijo.
 */
public final class UlidGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private UlidGenerator() {}

    public static String generate() {
        long time = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        char[] out = new char[26];
        // 10 chars timestamp
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        // 16 chars randomness
        int bitBuf = 0, bits = 0, idx = 10;
        for (byte b : random) {
            bitBuf = (bitBuf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out[idx++] = ALPHABET[(bitBuf >>> (bits - 5)) & 0x1F];
                bits -= 5;
            }
        }
        return new String(out);
    }
}