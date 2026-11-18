package com.nova.files.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault encryption (v1.19) — pure Kotlin, no Android imports, so it is
 * covered by the JVM unit tests (tests/VaultCryptoTest.kt).
 *
 * Design:
 * - One random 256-bit master key per vault. File contents are encrypted
 *   with that key (AES-256-GCM); the master key itself is wrapped with a
 *   key-encryption key (KEK) derived from the user's PIN
 *   (PBKDF2-HMAC-SHA256, 210,000 iterations, random 16-byte per-vault
 *   salt). Changing the PIN only re-wraps the master key — no file is
 *   re-encrypted. The master key is also wrapped under an Android Keystore
 *   key (see VaultKeystore) so fingerprint unlock works without the PIN.
 * - The master key and any derived KEK live in memory only while the
 *   vault is unlocked (VaultSession) and are wiped on lock. The PIN and
 *   the keys are never stored.
 * - Encrypted file format (same filename as before, so the vault listing
 *   does not change):
 *     "NVLT" (4 bytes) | version 0x01 (1 byte) | nonce (12 bytes) |
 *     AES-256-GCM ciphertext | GCM tag (16 bytes, appended by the cipher)
 *   A file without this header is a legacy plaintext file from v1.18 or
 *   earlier; [isEncrypted] tells the two apart and [migrateDirectory]
 *   encrypts the legacy ones in place.
 * - Wrapped master-key format: nonce (12 bytes) | ciphertext (32 bytes)
 *   | GCM tag (16 bytes) = 60 bytes total.
 * - Fail closed everywhere: a wrong key, a tampered byte, a truncated
 *   file or a bad header returns null/false — never plaintext, never a
 *   partial file left behind (callers delete the destination on false).
 */
object VaultCrypto {

    private val MAGIC = byteArrayOf(0x4E, 0x56, 0x4C, 0x54) // "NVLT"
    const val VERSION: Byte = 1
    const val PBKDF2_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + NONCE_BYTES // 17
    private const val WRAPPED_KEY_BYTES = NONCE_BYTES + KEY_BYTES + GCM_TAG_BITS / 8 // 60
    private const val BUFFER_BYTES = 64 * 1024

    fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Zero key material in place. Callers wipe KEKs and local key copies. */
    fun wipe(bytes: ByteArray) {
        bytes.fill(0)
    }

    /** PBKDF2-HMAC-SHA256, 256-bit output. The PIN char array is cleared
     *  (F-V19-4: the toCharArray() copy used to linger until GC). */
    fun deriveKek(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun generateMasterKey(): ByteArray = randomBytes(KEY_BYTES)

    /** Wrap a master key under a KEK. Returns nonce(12) + ciphertext + tag. */
    fun wrapMasterKey(kek: ByteArray, masterKey: ByteArray): ByteArray? {
        return try {
            val nonce = randomBytes(NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            nonce + cipher.doFinal(masterKey)
        } catch (e: Exception) {
            null
        }
    }

    /** Unwrap a master key. Null on any failure — wrong KEK, tampered or
     *  truncated blob. Never throws. */
    fun unwrapMasterKey(kek: ByteArray, wrapped: ByteArray): ByteArray? {
        if (wrapped.size != WRAPPED_KEY_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, NONCE_BYTES)
            )
            cipher.doFinal(wrapped, NONCE_BYTES, wrapped.size - NONCE_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    /** Encrypt a stream into the vault file format. Throws on I/O failure;
     *  the caller deletes the partial destination. */
    fun encryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        output.write(MAGIC)
        output.write(VERSION.toInt())
        output.write(nonce)
        CipherOutputStream(output, cipher).use { cipherOut ->
            input.copyTo(cipherOut, BUFFER_BYTES)
        }
    }

    /** Decrypt a vault-format stream. Returns false on any failure (bad
     *  header, wrong key, tampered or truncated data) — the caller must
     *  delete the partial destination. True only when the GCM tag
     *  verified, i.e. every byte was read and authenticated. */
    fun decryptStream(key: ByteArray, input: InputStream, output: OutputStream): Boolean {
        return try {
            val header = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < HEADER_BYTES) {
                val n = input.read(header, read, HEADER_BYTES - read)
                if (n < 0) return false
                read += n
            }
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) return false
            if (header[4] != VERSION) return false
            val nonce = header.copyOfRange(5, HEADER_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            // The GCM tag is verified when the cipher stream reaches EOF;
            // a tampered or truncated stream throws here (or on close).
            CipherInputStream(input, cipher).use { cipherIn ->
                cipherIn.copyTo(output, BUFFER_BYTES)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True only for files starting with the "NVLT" + version header.
     *  Any read error or short file is not encrypted. */
    fun isEncrypted(file: File): Boolean {
        return try {
            FileInputStream(file).use { ins ->
                val header = ByteArray(5)
                var read = 0
                while (read < 5) {
                    val n = ins.read(header, read, 5 - read)
                    if (n < 0) return false
                    read += n
                }
                header.copyOfRange(0, 4).contentEquals(MAGIC) && header[4] == VERSION
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Encrypt [plainFile] into [destFile]. False on any failure; the
     *  caller deletes the partial destination. */
    fun encryptFileTo(key: ByteArray, plainFile: File, destFile: File): Boolean {
        return try {
            FileInputStream(plainFile).use { ins ->
                FileOutputStream(destFile).use { out ->
                    encryptStream(key, ins, out)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Decrypt [encryptedFile] into [destFile]. False on any failure
     *  (wrong key, tampered, truncated); the caller deletes the partial
     *  destination. A plaintext (legacy) source is copied as-is so the
     *  same call works for both kinds of vault file. */
    fun decryptFileTo(key: ByteArray, encryptedFile: File, destFile: File): Boolean {
        return try {
            FileInputStream(encryptedFile).use { ins ->
                FileOutputStream(destFile).use { out ->
                    if (!isEncrypted(encryptedFile)) {
                        ins.copyTo(out, BUFFER_BYTES)
                        true
                    } else {
                        decryptStream(key, ins, out)
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Migrate a vault directory: encrypt every legacy plaintext file in
     * place (encrypt to a temp file in the same directory, then rename
     * over the original). Already-encrypted files and subdirectories are
     * skipped. Returns the names of files that could NOT be encrypted —
     * they are left untouched as plaintext and the caller must tell the
     * user (never silently). The temp file is deleted on failure.
     */
    fun migrateDirectory(dir: File, key: ByteArray): List<String> {
        val failures = mutableListOf<String>()
        val files = dir.listFiles() ?: return failures
        for (f in files) {
            if (!f.isFile || isEncrypted(f)) continue
            val tmp = File(dir, f.name + ".tmp-enc")
            var attempt = 0
            var dest = tmp
            while (dest.exists() && attempt < 100) {
                attempt++
                dest = File(dir, f.name + ".tmp-enc-$attempt")
            }
            val ok = encryptFileTo(key, f, dest)
            if (ok && dest.renameTo(f)) {
                // Encrypted in place; the original bytes are gone.
            } else {
                runCatching { dest.delete() }
                failures.add(f.name)
            }
        }
        return failures
    }

    /**
     * Restore a vault directory to plaintext (used when the PIN is
     * removed): decrypt every encrypted file in place. Two passes — first
     * decrypt each file to a temp and verify it, then rename the temps
     * over the originals — so a failure aborts before anything is
     * replaced. Returns the names that could not be decrypted; on a
     * non-empty result the caller must keep the PIN and report the names.
     */
    fun decryptDirectoryInPlace(dir: File, key: ByteArray): List<String> {
        val files = (dir.listFiles() ?: return emptyList())
            .filter { it.isFile && isEncrypted(it) }
        val temps = mutableListOf<Pair<File, File>>() // (temp, original)
        val failures = mutableListOf<String>()
        for (f in files) {
            val tmp = File(dir, f.name + ".tmp-dec")
            var attempt = 0
            var dest = tmp
            while (dest.exists() && attempt < 100) {
                attempt++
                dest = File(dir, f.name + ".tmp-dec-$attempt")
            }
            if (decryptFileTo(key, f, dest)) {
                temps.add(dest to f)
            } else {
                runCatching { dest.delete() }
                failures.add(f.name)
            }
        }
        if (failures.isNotEmpty()) {
            temps.forEach { (tmp, _) -> runCatching { tmp.delete() } }
            return failures
        }
        for ((tmp, original) in temps) {
            if (!tmp.renameTo(original)) failures.add(original.name)
        }
        return failures
    }
}
