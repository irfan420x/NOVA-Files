package com.nova.files.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for VaultCrypto (v1.19) — pure Kotlin, no Android.
 * Run with tests/run-tests.sh. No framework: main() prints the counts
 * and exits non-zero on any failure.
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) passed++ else {
            failed++
            println("FAIL: $name")
        }
    }

    val pin = "1234"
    val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
    val otherSalt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)

    // --- Key derivation ---
    val kek1 = VaultCrypto.deriveKek(pin, salt)
    val kek2 = VaultCrypto.deriveKek(pin, salt)
    check("KEK is deterministic for the same PIN+salt", kek1.contentEquals(kek2))
    check("KEK is 32 bytes", kek1.size == 32)
    check("KEK differs for a different salt", !VaultCrypto.deriveKek(pin, otherSalt).contentEquals(kek1))
    check("KEK differs for a different PIN", !VaultCrypto.deriveKek("4321", salt).contentEquals(kek1))

    // --- Master-key wrap / unwrap ---
    val master = VaultCrypto.generateMasterKey()
    check("master key is 32 bytes", master.size == 32)
    val wrapped = VaultCrypto.wrapMasterKey(kek1, master)
    check("wrap succeeds", wrapped != null)
    check("wrapped key is 60 bytes", wrapped!!.size == 60)
    check("unwrap round-trips", VaultCrypto.unwrapMasterKey(kek1, wrapped)?.contentEquals(master) == true)
    val wrongKek = VaultCrypto.deriveKek("9999", salt)
    check("unwrap with the wrong KEK fails", VaultCrypto.unwrapMasterKey(wrongKek, wrapped) == null)
    val tampered = wrapped.copyOf().also { it[20] = (it[20].toInt() xor 0x01).toByte() }
    check("unwrap with a tampered blob fails", VaultCrypto.unwrapMasterKey(kek1, tampered) == null)
    check("unwrap with a truncated blob fails", VaultCrypto.unwrapMasterKey(kek1, wrapped.copyOf(30)) == null)
    check("unwrap with a short blob fails", VaultCrypto.unwrapMasterKey(kek1, ByteArray(10)) == null)

    // --- Stream round-trips, several sizes (empty, 1 byte, header-sized,
    // 64 KiB, 1 MiB random) ---
    fun roundTrip(data: ByteArray): ByteArray? {
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptStream(master, ByteArrayInputStream(data), enc)
        val out = ByteArrayOutputStream()
        return if (VaultCrypto.decryptStream(master, ByteArrayInputStream(enc.toByteArray()), out)) out.toByteArray() else null
    }
    val sizes = listOf(0, 1, 17, 1024, 64 * 1024, 1024 * 1024)
    for (n in sizes) {
        val data = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
        check("stream round-trip, $n bytes", roundTrip(data)?.contentEquals(data) == true)
    }

    // --- Encrypted bytes must not leak plaintext, and must differ per call ---
    val plain = "secret vault contents 1234".toByteArray()
    val enc1 = ByteArrayOutputStream().also {
        VaultCrypto.encryptStream(master, ByteArrayInputStream(plain), it)
    }.toByteArray()
    val enc2 = ByteArrayOutputStream().also {
        VaultCrypto.encryptStream(master, ByteArrayInputStream(plain), it)
    }.toByteArray()
    check("ciphertext hides the plaintext", !enc1.toString(Charsets.UTF_8).contains("secret"))
    check("same plaintext encrypts differently each time (random nonce)", !enc1.contentEquals(enc2))
    check("encrypted blob has the NVLT header", enc1.copyOfRange(0, 4).contentEquals("NVLT".toByteArray()) && enc1[4] == VaultCrypto.VERSION)

    // --- Decryption failures: fail closed, never partial plaintext ---
    fun decryptOk(key: ByteArray, blob: ByteArray): Boolean {
        val out = ByteArrayOutputStream()
        return VaultCrypto.decryptStream(key, ByteArrayInputStream(blob), out)
    }
    check("decrypt with the wrong key fails", !decryptOk(wrongKek, enc1))
    val tamperedCt = enc1.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
    check("decrypt with a tampered tag fails", !decryptOk(master, tamperedCt))
    val tamperedMid = enc1.copyOf().also { it[30] = (it[30].toInt() xor 0x01).toByte() }
    check("decrypt with a tampered ciphertext byte fails", !decryptOk(master, tamperedMid))
    check("decrypt of a truncated blob fails", !decryptOk(master, enc1.copyOf(enc1.size - 5)))
    check("decrypt of a bad-magic blob fails", !decryptOk(master, "XXXX".toByteArray() + enc1.copyOfRange(4, enc1.size)))
    val badVersion = enc1.copyOf().also { it[4] = 0x02 }
    check("decrypt of a bad-version blob fails", !decryptOk(master, badVersion))
    check("decrypt of an empty blob fails", !decryptOk(master, ByteArray(0)))
    check("decrypt of a header-only blob fails", !decryptOk(master, enc1.copyOf(17)))

    // --- File-level helpers + isEncrypted ---
    val tmpDir = Files.createTempDirectory("vault-crypto-test").toFile()
    try {
        val plainFile = File(tmpDir, "note.txt").also { it.writeBytes("hello vault".toByteArray()) }
        check("plaintext file is not detected as encrypted", !VaultCrypto.isEncrypted(plainFile))
        val encFile = File(tmpDir, "note.txt.enc")
        check("encryptFileTo succeeds", VaultCrypto.encryptFileTo(master, plainFile, encFile))
        check("encrypted file is detected as encrypted", VaultCrypto.isEncrypted(encFile))
        check("encrypted file bytes differ from plaintext", !encFile.readBytes().contentEquals(plainFile.readBytes()))
        val decFile = File(tmpDir, "note.txt.dec")
        check("decryptFileTo succeeds", VaultCrypto.decryptFileTo(master, encFile, decFile))
        check("decrypted file matches the original", decFile.readBytes().contentEquals(plainFile.readBytes()))
        check("decryptFileTo with the wrong key fails", !VaultCrypto.decryptFileTo(wrongKek, encFile, File(tmpDir, "bad.out")))
        val emptyFile = File(tmpDir, "empty").also { it.writeBytes(ByteArray(0)) }
        check("empty file is not detected as encrypted", !VaultCrypto.isEncrypted(emptyFile))
        val shortFile = File(tmpDir, "short").also { it.writeBytes("NVL".toByteArray()) }
        check("short file is not detected as encrypted", !VaultCrypto.isEncrypted(shortFile))

        // --- migrateDirectory: plaintext in place -> encrypted, names kept ---
        val vaultDir = File(tmpDir, "vault").also { it.mkdirs() }
        val legacy1 = File(vaultDir, "photo.jpg").also { it.writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val legacy2 = File(vaultDir, "doc.txt").also { it.writeBytes("legacy doc".toByteArray()) }
        val alreadyEnc = File(vaultDir, "locked.bin")
        check("setup for migration: encrypt one file first", VaultCrypto.encryptFileTo(master, legacy1, alreadyEnc))
        val alreadyEncBytes = alreadyEnc.readBytes()
        File(vaultDir, "subfolder").mkdirs() // subdirectories are skipped
        val legacy1Bytes = legacy1.readBytes()
        val legacy2Bytes = legacy2.readBytes()
        val failures = VaultCrypto.migrateDirectory(vaultDir, master)
        check("migration reports no failures", failures.isEmpty())
        check("migrated file 1 is encrypted now", VaultCrypto.isEncrypted(legacy1))
        check("migrated file 2 is encrypted now", VaultCrypto.isEncrypted(legacy2))
        check("already-encrypted file is untouched", alreadyEnc.readBytes().contentEquals(alreadyEncBytes))
        val back1 = File(tmpDir, "back1")
        val back2 = File(tmpDir, "back2")
        check("migrated file 1 decrypts to the original", VaultCrypto.decryptFileTo(master, legacy1, back1) && back1.readBytes().contentEquals(legacy1Bytes))
        check("migrated file 2 decrypts to the original", VaultCrypto.decryptFileTo(master, legacy2, back2) && back2.readBytes().contentEquals(legacy2Bytes))
        check("migration is idempotent (second run changes nothing)", VaultCrypto.migrateDirectory(vaultDir, master).isEmpty())

        // --- decryptDirectoryInPlace: encrypted vault -> plaintext, and
        // the wrong key aborts without touching anything ---
        val wrongKeyFailures = VaultCrypto.decryptDirectoryInPlace(vaultDir, wrongKek)
        check("decrypt-in-place with the wrong key reports all files", wrongKeyFailures.size == 3)
        check("decrypt-in-place with the wrong key leaves files encrypted", VaultCrypto.isEncrypted(legacy1) && VaultCrypto.isEncrypted(legacy2) && VaultCrypto.isEncrypted(alreadyEnc))
        val restoreFailures = VaultCrypto.decryptDirectoryInPlace(vaultDir, master)
        check("decrypt-in-place reports no failures", restoreFailures.isEmpty())
        check("restored file 1 is plaintext again", !VaultCrypto.isEncrypted(legacy1) && legacy1.readBytes().contentEquals(legacy1Bytes))
        check("restored file 2 is plaintext again", !VaultCrypto.isEncrypted(legacy2) && legacy2.readBytes().contentEquals(legacy2Bytes))
    } finally {
        tmpDir.deleteRecursively()
    }

    println("VaultCryptoTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
