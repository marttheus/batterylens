package com.batterylens.data

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import com.batterylens.BatteryLensApp
import com.batterylens.service.BatteryMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backs up the history database and the settings/lifetime DataStore into one .zip, and restores it.
 * Only the two known entry names are ever written back, so a crafted archive cannot drop files
 * elsewhere in the app's storage.
 */
object Backup {
    private const val DB_ENTRY = AppDatabase.FILE_NAME
    private const val PREFS_ENTRY = "settings.preferences_pb"
    private const val SCHEMA_VERSION = 2

    private fun prefsFile(context: Context) = File(context.filesDir, "datastore/$PREFS_ENTRY")

    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext as BatteryLensApp
            // Fold the WAL into the main file so a single file copy is a consistent snapshot.
            app.database.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close()
            val dbFile = context.getDatabasePath(DB_ENTRY)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    val prefs = prefsFile(context)
                    if (prefs.exists()) {
                        zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                        prefs.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } != null
        }.getOrDefault(false)
    }

    sealed interface RestoreResult {
        data object Ok : RestoreResult
        data class Invalid(val reason: String) : RestoreResult
    }

    /**
     * Replaces the current data with the backup. The database is closed in the process, so the
     * caller must end the process afterwards (the app asks the user to reopen it).
     */
    suspend fun restore(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val tmpDir = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && (entry.name == DB_ENTRY || entry.name == PREFS_ENTRY)) {
                            File(tmpDir, entry.name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            } ?: return@withContext RestoreResult.Invalid("Não foi possível abrir o arquivo")

            val db = File(tmpDir, DB_ENTRY)
            if (!db.exists()) return@withContext RestoreResult.Invalid("O arquivo não é um backup do BatteryLens")
            val header = ByteArray(100)
            val read = db.inputStream().use { s ->
                var n = 0
                while (n < header.size) { val r = s.read(header, n, header.size - n); if (r < 0) break; n += r }
                n
            }
            if (read < 100 || String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") {
                return@withContext RestoreResult.Invalid("Banco de dados inválido")
            }
            val userVersion = ByteBuffer.wrap(header, 60, 4).int
            if (userVersion > SCHEMA_VERSION) {
                return@withContext RestoreResult.Invalid("Backup criado por uma versão mais nova do app")
            }

            BatteryMonitorService.stop(context)
            (context.applicationContext as BatteryLensApp).database.close()
            val target = context.getDatabasePath(DB_ENTRY)
            listOf("-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            db.copyTo(target, overwrite = true)
            File(tmpDir, PREFS_ENTRY).takeIf { it.exists() }?.let {
                val prefs = prefsFile(context)
                prefs.parentFile?.mkdirs()
                it.copyTo(prefs, overwrite = true)
            }
            RestoreResult.Ok
        } catch (e: Exception) {
            RestoreResult.Invalid("Falha ao restaurar: ${e.message}")
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
