package tech.anl.library.model.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "apps", indices = [(Index(value = ["name"], unique = true))])
data class App(
    @PrimaryKey
    val name: String,
    var category: String = "",
    var filesystemRequired: String = "",
    var supportsCli: Boolean = false,
    var supportsGui: Boolean = false,
    // "false", or the package name of a standalone app that should launch this one instead
    var supportsStandalone: String = "false",
    var isPaidApp: Boolean = false,
    var version: Long = 0
) : Parcelable