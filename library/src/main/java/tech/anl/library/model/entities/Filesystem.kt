package tech.anl.library.model.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "filesystem")
data class Filesystem(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    var name: String = "",
    var distributionType: String = "",
    var archType: String = "",
    var defaultUsername: String = "",
    var defaultPassword: String = "",
    var defaultVncPassword: String = "",
    var isAppsFilesystem: Boolean = false,
    var versionCodeUsed: String = "v0.0.0",
    var isCreatedFromBackup: Boolean = false,
    var isProtected: Boolean = false,
    // Which variant of the distribution image this is: "default" (minimal), "xfce", "lxde", ...
    // matching the Release Name column of the app's flavors.txt.
    var flavor: String = "default",
    var executionType: ExecutionType = ExecutionType.PROOT
) : Parcelable {
    override fun toString(): String {
        return "Filesystem(id=$id, name=$name, distributionType=$distributionType, archType=" +
                "$archType, isAppsFilesystem=$isAppsFilesystem, versionCodeUsed=$versionCodeUsed, " +
                "isCreatedFromBackup=$isCreatedFromBackup, isProtected=$isProtected, flavor=$flavor, " +
                "executionType=$executionType)"
    }
}