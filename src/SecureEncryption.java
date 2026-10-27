import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM authenticated encryption with PBKDF2-HMAC-SHA256 (1_000_000 iterations).
 * Output format (Base64): salt(16) || iv(12) || ciphertext+tag
 */
public class SecureEncryption {
    private static final int KEY_BITS = 256;
    private static final int DEFAULT_ITERATIONS = 1_000_000;
    private static final int AUDIO_ITERATIONS = 100;          // faster path for voice
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    public static String encrypt(String plaintext, String password) throws Exception {
        return encrypt(plaintext, password, DEFAULT_ITERATIONS);
    }

    public static String encrypt(String plaintext, String password, int iterations) throws Exception {
        if (plaintext == null || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("plaintext and password required");
        }
        return Base64.getEncoder().encodeToString(
                encrypt(plaintext.getBytes(StandardCharsets.UTF_8), password, iterations)
        );
    }

    public static byte[] encrypt(byte[] plaintext, String password) throws Exception {
        return encrypt(plaintext, password, DEFAULT_ITERATIONS);
    }

    public static byte[] encrypt(byte[] plaintext, String password, int iterations) throws Exception {
        SecureRandom random = new SecureRandom();

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);

        SecretKey key = deriveKey(password, salt, iterations);

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] out = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy(salt, 0, out, 0, salt.length);
        System.arraycopy(iv, 0, out, salt.length, iv.length);
        System.arraycopy(ciphertext, 0, out, salt.length + iv.length, ciphertext.length);
        return out;
    }

    public static String decrypt(String encryptedBase64, String password) throws Exception {
        return decrypt(encryptedBase64, password, DEFAULT_ITERATIONS);
    }

    public static String decrypt(String encryptedBase64, String password, int iterations) throws Exception {
        if (encryptedBase64 == null || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("encrypted data and password required");
        }
        byte[] plain = decrypt(Base64.getDecoder().decode(encryptedBase64), password, iterations);
        return new String(plain, StandardCharsets.UTF_8);
    }

    public static byte[] decrypt(byte[] combined, String password) throws Exception {
        return decrypt(combined, password, DEFAULT_ITERATIONS);
    }

    public static byte[] decrypt(byte[] combined, String password, int iterations) throws Exception {
        if (combined.length < SALT_BYTES + IV_BYTES + 16) {
            throw new IllegalArgumentException("ciphertext too short");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[combined.length - SALT_BYTES - IV_BYTES];

        System.arraycopy(combined, 0, salt, 0, SALT_BYTES);
        System.arraycopy(combined, SALT_BYTES, iv, 0, IV_BYTES);
        System.arraycopy(combined, SALT_BYTES + IV_BYTES, ciphertext, 0, ciphertext.length);

        SecretKey key = deriveKey(password, salt, iterations);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    /**
     * Derive the AES key once (for voice). Caller reuses the returned key
     * and only generates a fresh IV per frame.
     */
    public static SecretKey deriveAudioKey(String password, byte[] salt) throws Exception {
        return deriveKey(password, salt, AUDIO_ITERATIONS);
    }

    public static byte[] encryptWithKey(byte[] plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
        return out;
    }

    public static byte[] decryptWithKey(byte[] combined, SecretKey key) throws Exception {
        if (combined.length < IV_BYTES + 16) throw new IllegalArgumentException("too short");
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[combined.length - IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_BYTES);
        System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        return deriveKey(password, salt, DEFAULT_ITERATIONS);
    }

    private static SecretKey deriveKey(String password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
