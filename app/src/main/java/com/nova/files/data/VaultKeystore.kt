package com.nova.files.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore wrapper for the vault master key (v1.19).
 *
 * The master key is wrapped a second time — under a Keystore AES key
 * that requires biometric authentication for every use — so fingerprint
 * unlock works without the PIN. The Keystore key itself never leaves
 * the device's secure hardware/keystore.
 *
 * Wrapped format: nonce (12 bytes) | ciphertext (32 bytes) | GCM tag
 * (16 bytes) = 60 bytes, stored Base64 in SharedPreferences by FileRepo.
 *
 * The Keystore key is created with user-authentication required, so
 * wrapping/unwrapping only happens inside a BiometricPrompt
 * CryptoObject: the UI builds an encrypt/decrypt Cipher here, hands it
 * to the prompt, and only on biometric success uses it. Creating the
 * wrap therefore cannot happen silently on PIN unlock — the user taps
 * "Enable fingerprint unlock" once (see VaultScreen).
 *
 * Every function fails closed (null/false) — a missing keystore, a
 * key invalidated by a new biometric enrolment, or a tampered blob
 * just means "use your PIN". Callers never crash on this.
 */
object VaultKeystore {

    private const val ALIAS = "nova_vault_master"
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val WRAPPED_BYTES = NONCE_BYTES + 32 + GCM_TAG_BITS / 8 // 60

    private fun getOrCreateKey(): java.security.Key? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(ALIAS)) return ks.getKey(ALIAS, null)
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            gen.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    /** Cipher ready for ENCRYPT_MODE, for a BiometricPrompt CryptoObject.
     *  On biometric success the caller finishes the wrap with [finishWrap]. */
    fun newEncryptCipher(): Cipher? {
        return try {
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /** Complete a wrap started with [newEncryptCipher] after biometric
     *  success. Returns nonce + ciphertext + tag, or null on failure. */
    fun finishWrap(cipher: Cipher, masterKey: ByteArray): ByteArray? {
        return try {
            val nonce = cipher.iv ?: return null
            if (nonce.size != NONCE_BYTES) return null
            nonce + cipher.doFinal(masterKey)
        } catch (e: Exception) {
            null
        }
    }

    /** Cipher ready for DECRYPT_MODE for the stored blob, for a
     *  BiometricPrompt CryptoObject. On biometric success the caller
     *  finishes the unwrap with [finishUnwrap]. Null when there is no
     *  usable wrap (bad blob, invalidated key). */
    fun newDecryptCipher(wrapped: ByteArray): Cipher? {
        if (wrapped.size != WRAPPED_BYTES) return null
        return try {
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, NONCE_BYTES)
            )
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /** Complete an unwrap started with [newDecryptCipher] after biometric
     *  success. Returns the master key, or null on failure. */
    fun finishUnwrap(cipher: Cipher, wrapped: ByteArray): ByteArray? {
        if (wrapped.size != WRAPPED_BYTES) return null
        return try {
            cipher.doFinal(wrapped, NONCE_BYTES, wrapped.size - NONCE_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    /** Delete the Keystore key (PIN removal). Best-effort; failures are
     *  ignored — the stored wrap becomes useless without the key. */
    fun deleteKey() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
    }

    /** True when the Keystore key exists but is permanently invalidated
     *  (e.g. a new biometric was enrolled after fingerprint unlock was
     *  enabled). The stored Keystore wrap can then never unwrap again,
     *  so the caller should clear it — otherwise the "Enable fingerprint
     *  unlock" button never reappears (F-V19-2). Get-only: never mints
     *  a key. False on any other failure, so a transient Keystore error
     *  never destroys a usable wrap. */
    fun isKeyInvalidated(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (!ks.containsAlias(ALIAS)) return false
            val key = ks.getKey(ALIAS, null) ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, ByteArray(NONCE_BYTES))
            )
            false
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException lives in java.security on
            // Android only — it is not on the JVM classpath, so it cannot
            // be named directly here (the JVM tests compile this file).
            // Match by class name instead; any other failure is not an
            // invalidation and must not destroy a usable wrap.
            e.javaClass.name == "java.security.KeyPermanentlyInvalidatedException"
        }
    }
}
