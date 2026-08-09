package io.rigger.security.crypto;

import io.rigger.security.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.Base64;

/**
 * AES-256-GCM encryption for Rigger Secret values.
 *
 * In dev mode (RIGGER_MASTER_KEY not set), a random key is generated
 * in-memory at startup. Secrets will be lost on server restart — this
 * is logged as a clear warning. Production must set RIGGER_MASTER_KEY.
 */
@Component
public class SecretEncryptor {

    private static final Logger log = LoggerFactory.getLogger(SecretEncryptor.class);
    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH   = 12;
    private static final int    TAG_LENGTH  = 128;

    private final SecretKey masterKey;

    public SecretEncryptor(SecurityProperties props) {
        String keyStr = props.getMasterKey();
        if (keyStr == null || keyStr.isBlank()) {
            // Dev mode — generate ephemeral key
            this.masterKey = generateEphemeralKey();
            log.warn("╔══════════════════════════════════════════════════════╗");
            log.warn("║  RIGGER_MASTER_KEY not set.                         ║");
            log.warn("║  Using an ephemeral key — secrets lost on restart!  ║");
            log.warn("║  Production: export RIGGER_MASTER_KEY=$(openssl rand -base64 32) ║");
            log.warn("╚══════════════════════════════════════════════════════╝");
        } else {
            this.masterKey = loadKey(keyStr);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv     = newIv();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] enc    = cipher.doFinal(plaintext.getBytes());
            return Base64.getEncoder().encodeToString(iv)
                 + ":" + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            throw new SecurityOperationException("Encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            String[] parts = encrypted.split(":", 2);
            if (parts.length != 2) throw new SecurityOperationException("Invalid encrypted format");
            byte[] iv  = Base64.getDecoder().decode(parts[0]);
            byte[] enc = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(enc));
        } catch (SecurityOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityOperationException("Decryption failed", e);
        }
    }

    private byte[] newIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private SecretKey generateEphemeralKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    private SecretKey loadKey(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "RIGGER_MASTER_KEY is not valid Base64. Generate with: openssl rand -base64 32", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "RIGGER_MASTER_KEY must be exactly 32 bytes (256-bit). " +
                "Current length: " + keyBytes.length + " bytes. " +
                "Generate with: openssl rand -base64 32");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
