/*
 * SHILLGRAM: SHILLVPN built into the app.
 */
package io.github.audit0.shillgram.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The SHILLVPN secrets (subscription link and body, the site cabinet key,
 * the user's previous proxy): AES-256-GCM with a key that never leaves the
 * Android Keystore, ciphertext in private SharedPreferences. The desktop
 * client keeps the same items in the macOS Keychain.
 *
 * Values are never logged. When the Keystore fails (some broken vendor
 * builds) nothing is stored: the user pastes the link again next time.
 */
final class SecretStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "shillgram_vpn_secrets_v1";
    private static final String PREFS = "shillgram_vpn_secrets";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    SecretStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean write(String key, byte[] value) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            // The key name is authenticated too: a value cannot be moved
            // under another name.
            cipher.updateAAD(key.getBytes("UTF-8"));
            final byte[] iv = cipher.getIV();
            final byte[] sealed = cipher.doFinal(value);
            final ByteBuffer out = ByteBuffer.allocate(1 + iv.length + sealed.length);
            out.put((byte) iv.length).put(iv).put(sealed);
            prefs.edit().putString(key, Base64.encodeToString(out.array(), Base64.NO_WRAP)).apply();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    boolean write(String key, String value) {
        try {
            return write(key, value.getBytes("UTF-8"));
        } catch (Throwable e) {
            return false;
        }
    }

    /** The value, or null when absent or not readable any more. */
    byte[] read(String key) {
        final String stored = prefs.getString(key, null);
        if (stored == null) {
            return null;
        }
        try {
            final ByteBuffer in = ByteBuffer.wrap(Base64.decode(stored, Base64.NO_WRAP));
            final int ivLength = in.get() & 0xFF;
            if (ivLength != IV_LENGTH || in.remaining() <= ivLength) {
                return null;
            }
            final byte[] iv = new byte[ivLength];
            in.get(iv);
            final byte[] sealed = new byte[in.remaining()];
            in.get(sealed);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(key.getBytes("UTF-8"));
            return cipher.doFinal(sealed);
        } catch (Throwable e) {
            return null;
        }
    }

    String readString(String key) {
        final byte[] value = read(key);
        if (value == null) {
            return null;
        }
        try {
            return new String(value, "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    private static SecretKey key() throws Exception {
        final KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        final KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        final KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
