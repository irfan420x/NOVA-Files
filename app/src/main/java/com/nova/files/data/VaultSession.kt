package com.nova.files.data

/**
 * The vault master key, held in memory only while the vault is unlocked.
 * Never persisted, never written to disk. [clear] wipes the bytes — call
 * it whenever the vault locks (leaving the Vault screen, removing the
 * PIN) so a key never outlives the unlocked session.
 */
object VaultSession {

    @Volatile
    private var masterKey: ByteArray? = null

    @Synchronized
    fun set(key: ByteArray) {
        clear()
        masterKey = key.copyOf()
    }

    /** Returns a copy — callers wipe their copy when done. Null when locked. */
    @Synchronized
    fun get(): ByteArray? = masterKey?.copyOf()

    @Synchronized
    fun isUnlocked(): Boolean = masterKey != null

    @Synchronized
    fun clear() {
        masterKey?.fill(0)
        masterKey = null
    }
}
