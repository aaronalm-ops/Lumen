package app.lumen.player.data.drive

import java.io.IOException

/** What a row in the Drive folder browser stands for. */
enum class DriveNodeKind {
    /** The root of My Drive (folder id "root"). */
    MyDrive,

    /** The virtual list of folders shared directly with the user. */
    SharedWithMe,

    /** The root of one shared drive (its folder id is the drive id). */
    SharedDrive,

    /** An ordinary folder anywhere below those three. */
    Folder,
}

/**
 * One place in the Drive folder browser. [driveId] is the shared drive the folder lives in, ""
 * for My Drive and for folders shared from someone's My Drive; it decides the `corpora` of the
 * listing.
 */
data class DriveNode(
    val id: String,
    val name: String,
    val kind: DriveNodeKind,
    val driveId: String = "",
) {
    /** Only a real folder can be used; the three top-level entries cannot. */
    val selectable: Boolean get() = kind == DriveNodeKind.Folder

    companion object {
        val MyDrive = DriveNode(DriveApi.ROOT, "My Drive", DriveNodeKind.MyDrive)
        val SharedWithMe = DriveNode("shared-with-me", "Shared with me", DriveNodeKind.SharedWithMe)
    }
}

/** Thrown by the browser when the held grant lacks drive.readonly; the UI asks for a reconnect. */
class DriveReconnectException : IOException("drive.readonly not granted")
