package tech.anl.library.model.entities

import android.os.Parcelable
import androidx.room.* // ktlint-disable no-wildcard-imports
import kotlinx.parcelize.Parcelize

fun String.toServiceType(): ServiceType {
    return when (this) {
        // "vnc" and "xsdl" were stored by builds with graphical desktop sessions; SSH replaces them.
        "ssh", "vnc", "xsdl" -> ServiceType.Ssh
        else -> ServiceType.Unselected
    }
}

sealed class ServiceType : Parcelable {
    @Parcelize
    object Unselected : ServiceType() {
        override fun toString(): String {
            return "unselected"
        }
    }

    @Parcelize
    object Ssh : ServiceType() {
        override fun toString(): String {
            return "ssh"
        }
    }
}

class ServiceTypeConverter {
    @TypeConverter
    fun fromString(value: String): ServiceType {
        return value.toServiceType()
    }

    @TypeConverter
    fun fromServiceType(value: ServiceType): String {
        return value.toString()
    }
}

@Parcelize
@Entity(tableName = "session",
        foreignKeys = [ForeignKey(
                entity = Filesystem::class,
                parentColumns = arrayOf("id"),
                childColumns = arrayOf("filesystemId"),
                onDelete = ForeignKey.CASCADE)],
        indices = [
            Index(value = ["filesystemId"])
        ])
@TypeConverters(ServiceTypeConverter::class)
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    var name: String = "",
    var filesystemId: Long,
    var filesystemName: String = "",
    var active: Boolean = false,
    var username: String = "",
    var password: String = "",
    var vncPassword: String = "", // Legacy: unused since VNC sessions were removed; kept for the schema.
    var serviceType: ServiceType = ServiceType.Unselected,
    var port: Long = 2022, // TODO This can be removed. Any eventual port managing should be done at a high     er abstraction.
    var pid: Long = 0,
    var geometry: String = "", // Legacy: the VNC screen size; unused, kept for the schema.
    val isAppsSession: Boolean = false,
    var isProtected: Boolean = false
) : Parcelable {
    override fun toString(): String {
        return "Session(id=$id, name=$name, filesystemId=$filesystemId, filesystemName=" +
                "$filesystemName, active=$active, serviceType=$serviceType, port=$port, pid=" +
                "$pid, isAppsSession=$isAppsSession), isProtected=$isProtected"
    }
}