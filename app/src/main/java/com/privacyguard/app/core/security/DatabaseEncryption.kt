package com.privacyguard.app.core.security

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * SQLCipher wiring for Room (BRD NFR-S-06). The passphrase comes from
 * [SecureSecretStore], wrapped by an Android Keystore key, so the database file
 * is unreadable off the device.
 *
 * Installs that predate encryption have a plaintext database; it is encrypted
 * in place on first open with sqlcipher_export so user rules survive.
 */
object DatabaseEncryption {

    private const val TAG = "DatabaseEncryption"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)

    @Volatile private var libraryLoaded = false

    fun openHelperFactory(context: Context, databaseName: String): SupportOpenHelperFactory {
        loadLibrary()
        val appContext = context.applicationContext
        val store = SecureSecretStore.getInstance(appContext)
        val dbFile = appContext.getDatabasePath(databaseName)

        // An encrypted file with no stored passphrase (prefs cleared) can never be
        // opened again; start fresh rather than crash on every launch.
        if (dbFile.exists() && !isPlaintext(dbFile) && !store.hasDatabasePassphrase()) {
            Log.w(TAG, "Encrypted database has no passphrase; recreating it")
            appContext.deleteDatabase(databaseName)
        }

        val passphrase = try {
            store.getOrCreateDatabasePassphrase()
        } catch (e: Exception) {
            // The Keystore key is gone, so the stored passphrase cannot be unwrapped.
            Log.w(TAG, "Database key unavailable; recreating database", e)
            appContext.deleteDatabase(databaseName)
            store.resetDatabasePassphrase()
            store.getOrCreateDatabasePassphrase()
        }

        if (isPlaintext(dbFile)) {
            try {
                encryptInPlace(dbFile, passphrase)
            } catch (e: Exception) {
                // Never fall back to the plaintext file.
                Log.e(TAG, "Encrypting the existing database failed; recreating it", e)
                appContext.deleteDatabase(databaseName)
            }
        }
        // The factory keeps its own copy; zero ours.
        return SupportOpenHelperFactory(passphrase.copyOf()).also { passphrase.fill(0) }
    }

    private fun loadLibrary() {
        if (libraryLoaded) return
        synchronized(this) {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
        }
    }

    internal fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) return false
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    private fun encryptInPlace(dbFile: File, passphrase: ByteArray) {
        val tmp = File(dbFile.parentFile, dbFile.name + ".encrypting")
        deleteWithSidecars(tmp)

        val plain = SQLiteDatabase.openDatabase(
            dbFile.path, "", null, SQLiteDatabase.OPEN_READWRITE, null as SQLiteDatabaseHook?
        )
        try {
            val version = plain.version
            plain.rawExecSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                tmp.path, String(passphrase, StandardCharsets.US_ASCII)
            )
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            // sqlcipher_export does not copy user_version, which Room uses for migrations.
            plain.rawExecSQL("PRAGMA encrypted.user_version = $version")
            plain.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }

        deleteWithSidecars(dbFile)
        if (!tmp.renameTo(dbFile)) error("could not replace ${dbFile.name} with its encrypted copy")
        Log.i(TAG, "Encrypted existing database ${dbFile.name}")
    }

    private fun deleteWithSidecars(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }
}
