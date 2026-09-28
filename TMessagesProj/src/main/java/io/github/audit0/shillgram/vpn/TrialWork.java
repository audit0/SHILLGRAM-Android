/*
 * SHILLGRAM: SHILLVPN built into the app.
 */
package io.github.audit0.shillgram.vpn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The free trial's request pieces, as the desktop client builds them: a
 * keyed hash of the device id (the site keeps only a hash of that) and the
 * proof of work the site asks for, so one script cannot take every trial.
 * Plain Java; runs off the main thread.
 */
public final class TrialWork {

    private TrialWork() {
    }

    /** hex(HMAC-SHA256(key = "SHILLGRAM app trial v1", id)). */
    public static String deviceHash(byte[] id) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    "SHILLGRAM app trial v1".getBytes(StandardCharsets.US_ASCII),
                    "HmacSHA256"));
            return hex(mac.doFinal(id));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }

    /** "shillvpn/app-trial" \0 device \0 hour \0 */
    public static byte[] prefix(String device, long hour) {
        final String text = "shillvpn/app-trial\0" + device + "\0" + hour + "\0";
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The smallest-found n such that sha256(prefix + decimal(n)) starts with
     * 24 zero bits: about 16 million tries spread over the cores.
     */
    public static String solve(byte[] prefix) {
        final int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final AtomicLong found = new AtomicLong(0);
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            workers[t] = new Thread(() -> {
                final MessageDigest base = digest();
                base.update(prefix);
                MessageDigest cloneable = null;
                try {
                    base.clone();
                    cloneable = base;
                } catch (CloneNotSupportedException ignored) {
                }
                final MessageDigest plain = cloneable == null ? digest() : null;
                final byte[] number = new byte[24];
                for (long n = offset + 1; found.get() == 0; n += threads) {
                    final int length = writeDecimal(n, number);
                    final byte[] hash;
                    try {
                        if (cloneable != null) {
                            final MessageDigest context = (MessageDigest) cloneable.clone();
                            context.update(number, number.length - length, length);
                            hash = context.digest();
                        } else {
                            plain.reset();
                            plain.update(prefix);
                            plain.update(number, number.length - length, length);
                            hash = plain.digest();
                        }
                    } catch (CloneNotSupportedException e) {
                        return;
                    }
                    if (hash[0] == 0 && hash[1] == 0 && hash[2] == 0) {
                        found.compareAndSet(0, n);
                    }
                }
            }, "shillvpn-work");
            workers[t].start();
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Long.toString(found.get());
    }

    /** Writes n right-aligned into buffer, returns its length. */
    private static int writeDecimal(long n, byte[] buffer) {
        int position = buffer.length;
        do {
            buffer[--position] = (byte) ('0' + (n % 10));
            n /= 10;
        } while (n != 0);
        return buffer.length - position;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return out.toString();
    }
}
