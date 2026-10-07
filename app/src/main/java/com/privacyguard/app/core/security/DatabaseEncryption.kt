package com.privacyguard.app.core.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

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
    private const val RECOVERY_PREFS = "database_recovery"
    private const val KEY_RECREATED = "recreated"
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
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
            Log.w(TAG, "Encrypted database has no passphrase; recreating all encrypted databases")
            recreateAllEncrypted(appContext)
        }

        val passphrase = try {
            store.getOrCreateDatabasePassphrase()
        } catch (e: GeneralSecurityException) {
            // Only a key that is permanently gone justifies deleting data; anything
            // else (a busy Keystore) propagates and is retried on the next open.
            if (!isPermanentKeyLoss(e)) throw e
            Log.w(TAG, "Database key lost; recreating all encrypted databases", e)
            // Every database shares this passphrase, so none of them can be opened.
            recreateAllEncrypted(appContext)
            store.resetDatabasePassphrase()
            store.getOrCreateDatabasePassphrase()
        }

        if (isPlaintext(dbFile)) {
            try {
                encryptInPlace(dbFile, passphrase)
            } catch (e: Exception) {
                // Never fall back to the plaintext file.
                Log.e(TAG, "Encrypting the existing database failed; recreating it", e)
                recreate(appContext, databaseName)
            }
        }
        // The factory keeps its own copy; zero ours.
        return SupportOpenHelperFactory(passphrase.copyOf()).also { passphrase.fill(0) }
    }

    /**
     * Databases deleted because they could not be decrypted, so the UI can say so
     * (e.g. offer to restore rules from a backup) instead of starting over silently.
     */
    fun recreatedDatabases(context: Context): Set<String> =
        recoveryPrefs(context).getStringSet(KEY_RECREATED, emptySet()).orEmpty()

    fun acknowledgeRecreated(context: Context, databaseName: String) {
        val prefs = recoveryPrefs(context)
        val remaining = prefs.getStringSet(KEY_RECREATED, emptySet()).orEmpty() - databaseName
        prefs.edit().putStringSet(KEY_RECREATED, remaining).commit()
    }

    internal fun isPermanentKeyLoss(e: Throwable): Boolean =
        e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException ||
            e is AEADBadTagException

    private fun recreateAllEncrypted(context: Context) {
        context.databaseList()
            .filterNot { name -> SIDECAR_SUFFIXES.any { name.endsWith(it) } }
            .filter { name ->
                val file = context.getDatabasePath(name)
                file.isFile && file.length() > 0 && !isPlaintext(file)
            }
            .forEach { recreate(context, it) }
    }

    private fun recreate(context: Context, databaseName: String) {
        // Record first: if the process dies after the delete, the loss is still reported.
        val prefs = recoveryPrefs(context)
        val recreated = prefs.getStringSet(KEY_RECREATED, emptySet()).orEmpty() + databaseName
        prefs.edit().putStringSet(KEY_RECREATED, recreated).commit()
        context.deleteDatabase(databaseName)
        Log.w(TAG, "Recreated $databaseName")
    }

    private fun recoveryPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)

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
