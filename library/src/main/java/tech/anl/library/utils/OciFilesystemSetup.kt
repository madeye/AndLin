package tech.anl.library.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.oci.CommandTarProcessFactory
import tech.anl.library.oci.LayerExtractor
import tech.anl.library.oci.OciFilesystemInstaller
import tech.anl.library.oci.OciInstallProgress
import tech.anl.library.oci.OciRegistryClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Builds a PRoot filesystem's root from its OCI image; see [FilesystemImages]. */
class OciFilesystemSetup(context: Context, private val anlFiles: AnlFiles) {
    private val extractor by lazy { LayerExtractor(CommandTarProcessFactory.toybox(), warn = { Log.w(TAG, it) }) }

    private val installer: OciFilesystemInstaller by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val client = OciRegistryClient(http, registryCandidates = { RegistryMirror.candidates(it) })
        OciFilesystemInstaller(client, extractor, File(context.cacheDir, "oci"))
    }

    /** Pulls the filesystem's image into filesDir/<id>, reporting human-readable progress. */
    suspend fun install(filesystem: Filesystem, onProgress: (String) -> Unit) {
        // The canonical ghcr.io reference: the client picks mirrors itself and can fall back.
        val imageRef = FilesystemImages.imageRef(filesystem.distributionType, filesystem.flavor)
        val rootfs = File(anlFiles.filesDir, "${filesystem.id}")
        installer.install(imageRef, anlFiles.getAbi(), rootfs) { progress -> onProgress(describe(progress)) }
    }

    /**
     * Unpacks a legacy rootfs.tar.gz (the release-asset flow) into the filesystem. Support assets
     * since v1.4.21 leave this to the app rather than to extractFilesystem.sh.
     */
    suspend fun extractTarball(filesystem: Filesystem, onProgress: (String) -> Unit): Boolean {
        val rootfs = File(anlFiles.filesDir, "${filesystem.id}")
        val tarball = File(rootfs, "support/rootfs.tar.gz")
        if (!tarball.exists()) return false
        extractor.extract(tarball, rootfs) { percent -> onProgress("Extracting filesystem ($percent%)") }
        return true
    }

    private fun describe(progress: OciInstallProgress): String = when (progress) {
        is OciInstallProgress.Resolving -> "Resolving ${progress.reference}"
        is OciInstallProgress.Downloading -> "Downloading layer ${progress.layer}/${progress.layerCount} (${progress.percent}%)"
        is OciInstallProgress.Extracting -> "Extracting layer ${progress.layer}/${progress.layerCount} (${progress.percent}%)"
        is OciInstallProgress.Notice -> progress.message
        OciInstallProgress.Finalizing -> "Finalizing"
    }

    companion object {
        private const val TAG = "OciFilesystemSetup"
    }
}
