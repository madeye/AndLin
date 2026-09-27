package tech.anl.library.model.entities

/**
 * The variant of a distribution's filesystem image, stored in [Filesystem.flavor]. New filesystems
 * only get the headless server image; filesystems created by older builds may still record a
 * desktop flavor ("default", "xfce", "lxde"), which [tech.anl.library.utils.FilesystemImages]
 * keeps resolving.
 */
object FilesystemFlavor {
    const val SERVER = "server"
}
