package dev.drecorder.ui.library

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where recordings live when the user has not picked a directory.
 *
 * App-specific external storage needs no permission on any supported API level and
 * is removed with the app, which is the right default for files the user can still
 * share out explicitly.
 */
fun defaultOutputDirectory(context: Context): File? =
    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
