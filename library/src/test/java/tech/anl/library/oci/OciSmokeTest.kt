package tech.anl.library.oci

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Manual end-to-end check against the real GHCR: pulls userland-alpine for arm64 and extracts it
 * with the host tar. Network-dependent, so it is ignored; remove @Ignore to run it locally.
 * Set OCI_SMOKE_DIR to choose where the rootfs goes and OCI_SMOKE_REGISTRIES (comma-separated,
 * e.g. "ghcr.nju.edu.cn,ghcr.io") to exercise mirrors.
 */
class OciSmokeTest {
    @Ignore("Hits the network; run manually")
    @Test
    fun `pulls and extracts userland-alpine from GHCR`() = runBlocking {
        val base = File(System.getenv("OCI_SMOKE_DIR") ?: System.getProperty("java.io.tmpdir") + "/oci-smoke")
        base.deleteRecursively()
        val rootfs = File(base, "rootfs")
        val registries = System.getenv("OCI_SMOKE_REGISTRIES")?.split(',')?.map { it.trim() }
        val client = OciRegistryClient(OkHttpClient(), registryCandidates = { registries ?: listOf(it) })
        val installer = OciFilesystemInstaller(
            client,
            LayerExtractor(CommandTarProcessFactory.hostTar(), warn = { println("warning: $it") }),
            File(base, "cache"),
        )

        var last: OciInstallProgress? = null
        val started = System.currentTimeMillis()
        installer.install("ghcr.io/cypherpunkarmory/userland-alpine:latest", "arm64-v8a", rootfs) {
            if (it != last && (it !is OciInstallProgress.Downloading || it.percent % 25 == 0) &&
                (it !is OciInstallProgress.Extracting || it.percent % 25 == 0)
            ) {
                println(it)
            }
            last = it
        }
        println("Installed in ${System.currentTimeMillis() - started} ms into $rootfs")
        println(File(rootfs, OciFilesystemInstaller.STATE_FILE).readText())
        listOf("bin/busybox", "etc/os-release", "support/busybox", "support/extractFilesystem.sh").forEach {
            val f = File(rootfs, it)
            check(f.exists()) { "$it missing" }
            println("$it: ${f.length()} bytes, writable=${f.canWrite()}")
        }
    }
}
