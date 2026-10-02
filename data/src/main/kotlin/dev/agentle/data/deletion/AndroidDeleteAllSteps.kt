package dev.agentle.data.deletion

import android.app.ActivityManager
import android.content.Context
import dev.agentle.core.database.DatabaseProvider
import dev.agentle.core.security.CryptoEraser
import java.io.File

/** Stops every data producer (workers, listeners, the foreground service); implemented by the app wiring. */
fun interface DataProducers {
    suspend fun stopAll()
}

/** Revokes remote grants and tokens (Google Health, ChatGPT sign-in); best effort; implemented by the app wiring. */
fun interface RemoteRevoker {
    suspend fun revokeAll()
}

/**
 * The production [DeleteAllSteps]: the app's private directories are emptied except the delete-all marker, the keys
 * are crypto-erased ([CryptoEraser]), and the system clears the app data last.
 */
internal class AndroidDeleteAllSteps(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val eraser: CryptoEraser,
    private val producers: DataProducers,
    private val remote: RemoteRevoker,
    private val marker: File,
) : DeleteAllSteps {
    private val roots: List<File>
        get() = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.getDatabasePath("x").parentFile)

    override suspend fun stopProducers() = producers.stopAll()

    @Suppress("TooGenericExceptionCaught") // Remote revocation is best effort: no failure may block the local wipe.
    override suspend fun revokeRemote() {
        try {
            remote.revokeAll()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            // The tokens are erased with the vault below; the remote grant expires or is revoked by the user there.
        }
    }

    override suspend fun closeDatabase() = provider.close()

    override suspend fun deleteFilesAndKeys() {
        eraser.eraseAll()
        roots.forEach { root -> root.listFiles()?.filter { it != marker && it != marker.parentFile }?.forEach { it.deleteRecursively() } }
    }

    override suspend fun verifyGone(): Boolean =
        roots.all { root -> root.listFiles().orEmpty().all { it == marker || it == marker.parentFile } } &&
            marker.parentFile?.listFiles().orEmpty().all { it == marker }

    override suspend fun clearApplicationUserData() {
        context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
    }
}
