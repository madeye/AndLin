package tech.anl.library.utils

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class AssetFileClearer(
    private val anlFiles: AnlFiles,
    private val assetDirectoryNames: Set<String>,
    private val busyboxExecutor: BusyboxExecutor,
    private val logger: Logger = LogcatLogger()
) {
    @Throws(FileNotFoundException::class, IllegalStateException::class)
    suspend fun clearAllSupportAssets() {
        if (!anlFiles.filesDir.exists()) {
            val exception = FileNotFoundException()
            logger.addExceptionBreadcrumb(exception)
            throw exception
        }
        if (!anlFiles.busybox.exists()) {
            val exception = IllegalStateException("Busybox missing")
            logger.addExceptionBreadcrumb(exception)
            throw exception
        }
        clearFilesystemSupportAssets()
        clearTopLevelAssets(assetDirectoryNames)
    }

    @Throws(IOException::class)
    private suspend fun clearTopLevelAssets(assetDirectoryNames: Set<String>) {
        val files = anlFiles.filesDir.listFiles() ?: return
        for (file in files) {
            if (!file.isDirectory) continue
            if (!assetDirectoryNames.contains(file.name)) continue
            if (file.name == "support") continue
            if (busyboxExecutor.recursivelyDelete(file.absolutePath) !is SuccessfulExecution) {
                val exception = IOException()
                logger.addExceptionBreadcrumb(exception)
                throw exception
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun clearFilesystemSupportAssets() {
        val files = anlFiles.filesDir.listFiles() ?: return
        for (file in files) {
            if (!file.isDirectory || file.name.toIntOrNull() == null) continue

            val supportDirectory = File("${file.absolutePath}/support")
            if (!supportDirectory.exists() || !supportDirectory.isDirectory) continue

            val supportFiles = supportDirectory.listFiles() ?: continue
            for (supportFile in supportFiles) {
                // Exclude directories and hidden files.
                if (supportFile.isDirectory || supportFile.name.first() == '.') continue
                // Use deleteRecursively to match functionality above
                if (busyboxExecutor.recursivelyDelete(supportFile.path) !is SuccessfulExecution) {
                    val exception = IOException()
                    logger.addExceptionBreadcrumb(exception)
                    throw exception
                }
            }
        }
    }
}