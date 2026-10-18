package dev.drecorder

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Turns a SAF tree uri into a real directory path — but only when the tree really
 * is a local filesystem directory.
 *
 * `AudioRecord`-backed writers take a [File], not a content uri, so a SAF picker
 * result is useless unless it maps onto primary/shared storage. Cloud providers
 * return null here and the caller falls back to the app-specific directory rather
 * than recording into a void.
 */
object DocumentTree {

    fun uriToDirectory(context: Context, uri: Uri): File? {
        val authority = uri.authority ?: return null
        if (authority != "com.android.externalstorage.documents") return null

        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = treeId.split(':', limit = 2)
        if (parts.isEmpty()) return null

        val volume = parts[0]
        val relative = parts.getOrNull(1).orEmpty()

        val root = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory()

            else -> File("/storage/$volume")
        }
        if (!root.isDirectory) return null

        val directory = if (relative.isEmpty()) root else File(root, relative)
        return if (directory.isDirectory) directory else null
    }
}
